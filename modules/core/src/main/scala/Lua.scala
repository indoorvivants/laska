/*
 * Copyright 2020 Anton Sviridov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laska

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.string.strncpy
import lua.types.*
import lua.functions.*
import scala.scalanative.runtime.RawPtr
import scala.scalanative.runtime.Intrinsics

case class GlobalName private[laska] (nm: String)
case class FieldName private[laska] (nm: String)

enum LuaOp[Result]:
  case RaiseError(message: String) extends LuaOp[Unit]

  case PeekNumber extends LuaOp[Long]
  case PeekString extends LuaOp[String]

  case PushNumber(num: Double) extends LuaOp[Unit]
  case PushString(str: String) extends LuaOp[Unit]

  case LoadString(str: String) extends LuaOp[Unit]

  case GetName(nm: Name) extends LuaOp[Unit]

  case GetGlobal(nm: String)         extends LuaOp[GlobalName]
  case GetField(nm: String)          extends LuaOp[FieldName]
  case Call(args: Int, results: Int) extends LuaOp[Unit]

  private[laska] case FlatMap[Result, Result1](
      cur: LuaOp[Result],
      next: Result => LuaOp[Result1]
  ) extends LuaOp[Result1]

  private[laska] case Mapped[Result, Result1](
      cur: LuaOp[Result],
      transform: Result => Result1
  ) extends LuaOp[Result1]

  inline def flatMap[B](inline f: Result => LuaOp[B]): LuaOp[B] =
    FlatMap(this, f)

  inline def *>[B](f: LuaOp[B]): LuaOp[B] =
    FlatMap(this, _ => f)

  inline def void: LuaOp[Unit] =
    map(_ => ())

  inline def map[B](inline f: Result => B): LuaOp[B] =
    Mapped(this, f)
end LuaOp

def run[T](state: Ptr[lua_State], program: LuaOp[T])(using
    Zone
): Either[String, T] =
  val L = state
  import lua.functions.*

  inline def withTop[A](inline f: Int => A) =
    f(lua_gettop(L))

  inline def err[T](message: String): Either[String, T] =
    run(L, LuaOp.RaiseError(message)).asInstanceOf[Either[String, T]]

  inline def interp[T](op: LuaOp[T]): Either[String, T] =
    run(L, op)

  program match
    case LuaOp.RaiseError(message) =>
      lua_pushstring(L, toCString(message))
      lua_error(L)
      Left(message)

    case LuaOp.Call(args, results) =>
      lua_callk(
        L,
        args,
        results,
        0L.asInstanceOf[lua_KContext],
        lua_KFunction(null)
      )
      Right(())

    case LuaOp.GetName(nm) =>
      val chain = nm.toList
      chain match
        case head :: Nil =>
          interp(LuaOp.GetGlobal(head).map(_ => ()))
        case Nil =>
          Left("empty name!")
        case head :: field :: restOfFields =>
          interp(
            LuaOp
              .GetGlobal(head)
              *>
                restOfFields
                  .foldLeft(LuaOp.GetField(field)) { case (res, newField) =>
                    res *> LuaOp.GetField(newField)
                  }
                  .void
          )
      end match

    case LuaOp.GetGlobal(nm) =>
      if lua_getglobal(L, toCString(nm)) == -1 then
        err(s"failed to get global name $nm")
      else Right(GlobalName(nm))

    case LuaOp.GetField(nm) =>
      if lua_getfield(L, -1, toCString(nm)) == -1 then
        err(s"failed to get field name `$nm`")
      else Right(FieldName(nm))

    case LuaOp.PeekNumber =>
      if lua_isnumber(L, -1) != 1 then err("expected a number")
      else Right(lua_tointegerx(L, -1, null).asInstanceOf[Long])

    case LuaOp.PushNumber(num) =>
      lua_pushnumber(L, lua_Number(num))
      Right(())

    case LuaOp.PushString(str) =>
      lua_pushstring(L, toCString(str))
      Right(())

    case LuaOp.PeekString =>
      if lua_isstring(L, -1) != 1 then err("expected a string")
      else
        val ptr = stackalloc[size_t](1)
        !ptr = lua_rawlen(L, -1).asInstanceOf[size_t]
        val dest = alloc[Byte]((!ptr).value)
        // TODO: free luaString
        val luaString = lua_tolstring(L, -1, ptr)
        val cpy       = strncpy(dest, luaString, (!ptr).value)

        Right(fromCString(cpy))

    case LuaOp.LoadString(str) =>
      luaL_loadstring(L, toCString(str))
      Right(())

    case LuaOp.FlatMap(cur, next) =>
      val result = run(L, cur)
      result.flatMap { r =>
        run(L, next(r))
      }
    case LuaOp.Mapped(cur, transform) =>
      val result = run(L, cur)
      result.map { r =>
        transform(r)
      }
  end match
end run

def withLuaState[A](f: Ptr[lua_State] => A)(using Zone) =
  val stats = alloc[CStruct2[Long, Long]](1)

  val luaAllocator = lua_Alloc.apply {
    CFuncPtr4.fromScalaFunction { (ud, ptr, osize, nsize) =>
      val state = ud.asInstanceOf[Ptr[CStruct2[Long, Long]]]

      if nsize == 0.toULong then
        (!state)._2 = (!state)._2 + osize.asInstanceOf[ULong].toLong
        scalanative.libc.stdlib.free(ptr)
        null
      else
        (!state)._1 = (!state)._1 + nsize.asInstanceOf[ULong].toLong
        scalanative.libc.stdlib.realloc(ptr, nsize.asInstanceOf[ULong])
    }
  }

  val state: Ptr[lua_State] =
    lua_newstate(luaAllocator, stats.asInstanceOf[Ptr[Byte]])

  lua_atpanic(
    state,
    lua_CFunction(CFuncPtr1.fromScalaFunction { state =>
      Zone { implicit z =>
        System.err.println(
          Console.RED + Console.BOLD + "[LUA PANIC]: " + Console.RESET + Console.BOLD + run(
            state,
            LuaOp.PeekString
          ).fold(
            errGetting => s"failed to get the error object: $errGetting",
            identity
          ) + Console.RESET
        )
      }
      -1
    })
  )

  luaL_openlibs(state)

  val result = f(state)

  lua_close(state)
  result
end withLuaState

import scala.language.dynamics
class Name private (val chain: List[String]) extends Dynamic:
  def selectDynamic(name: String): Name = new Name(chain :+ name)

  def toList = chain

object Name extends Name(Nil)

import compiletime.*

inline def getOpts[Arguments <: Tuple](args: Array[Any])(
    cur: List[LuaOp[Unit]]
): List[LuaOp[Unit]] =
  inline erasedValue[Arguments] match
    case _: EmptyTuple => cur
    case _: (t *: next_t) =>
      inline erasedValue[t] match
        case _: String =>
          getOpts[next_t](args.tail)(
            LuaOp.PushString(args.head.asInstanceOf[String]) :: cur
          )
        case _: Int =>
          getOpts[next_t](args.tail)(
            LuaOp.PushNumber(args.head.asInstanceOf[Int].toDouble) :: cur
          )

inline def functionCall[Arguments <: Tuple, Result <: String | Double](
    name: Name
): Arguments => LuaOp[Result] =
  args =>
    val arr   = args.toArray.asInstanceOf[Array[Any]]
    val stack = LuaOp.GetName(name) :: getOpts[Arguments](arr)(Nil)

    val result = inline erasedValue[Result] match
      case _: String => LuaOp.PeekString.asInstanceOf[LuaOp[Result]]

    stack.foldLeft(LuaOp.GetName(name)) { (o, nxt) => o *> nxt } *> LuaOp.Call(
      arr.size,
      1
    ) *> result

inline def functionDefinition

@main def hello =
  import LuaOp.*

  val stringLower =
    functionCall[String *: EmptyTuple, String](Name.string.lower)

  val prog: LuaOp[String] =
    for lowered <- stringLower("HOWDy" *: EmptyTuple)
    yield lowered

  Zone { implicit z =>
    println(withLuaState(L => run(L, prog)))
  }

end hello
