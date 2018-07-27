// Copyright (c) 2017-2018 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
// NOTE: This file has been partially copy/pasted from scalameta/scalameta.
package rsc.rules.pretty

import rsc.lexis._
import rsc.pretty._
import rsc.rules.semantics._
import scala.collection.mutable
import scala.meta._
import scala.meta.internal.{semanticdb => s}
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Scala.{Descriptor => d}
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.internal.semanticdb.SymbolInformation.{Property => p}
import scalafix.v0._

class SemanticdbPrinter(env: Env, index: DocumentIndex) extends Printer {
  def pprint(tree: s.Tree): Unit = tree match {
    case s.OriginalTree(range) =>
      str(index.substring(range).get)
    case s.ApplyTree(fn, args) =>
      pprint(fn)
      rep("(", args, ", ", ")")(pprint)
    case s.TypeApplyTree(fn, targs) =>
      pprint(fn)
      rep("[", targs, ", ", "]")(pprint)
    case s.SelectTree(qual, id) =>
      // FIXME: https://github.com/twitter/rsc/issues/142
      val needsParens = qual match {
        case s.OriginalTree(range) =>
          val originalTerm = index.substring(range).get.parse[Term].get
          originalTerm match {
            case _: Term.ApplyInfix => true
            case _ => false
          }
        case _ => false
      }
      if (needsParens) str("(")
      pprint(qual)
      if (needsParens) str(")")
      str(".")
      pprint(id.get.sym)
    case s.IdTree(sym) =>
      sym.owner.desc match {
        case d.None =>
          pprint(sym)
        case _: d.Package | _: d.Term =>
          pprint(s.SelectTree(s.IdTree(sym.owner), Some(s.IdTree(sym))))
        case d.Type(name) =>
          if (env.lookupThis(name) == sym.owner) {
            pprint(sym.owner)
            str(".this.")
            pprint(sym)
          } else {
            // TODO: This looks incorrect.
            // str(".")
            ???
          }
        case desc => sys.error(s"unsupported desc $desc")
      }
    case s.FunctionTree(params, term) =>
      str("{")
      params match {
        case Seq() => str("() => ")
        case Seq(id) =>
          pprint(id.sym)
          str(" => ")
        case _ =>
          rep("(", params, ", ", ") => ")(id => pprint(id.sym))
      }
      pprint(term)
      str("}")
    case s.MacroExpansionTree(expandee, _) =>
      expandee match {
        case s.ApplyTree(
            s.IdTree("scala/reflect/package.materializeClassTag()."),
            Nil) =>
          str("_root_.scala.reflect.`package`.classTag")
        case _ =>
          pprint(expandee)
      }
    case _ => sys.error(s"unsupported tree $tree")
  }

  def pprint(tpe: s.Type): Unit = {
    def prefix(tpe: s.Type): Unit = {
      tpe match {
        case s.TypeRef(pre, sym, args) =>
          if (sym.startsWith("scala/Function") &&
              args.exists(_.isInstanceOf[s.ByNameType])) {
            var params :+ ret = args
            if (params.length != 1) str("(")
            rep(params, ", ") { param =>
              // FIXME: https://github.com/twitter/rsc/issues/142
              str("(")
              normal(param)
              str(")")
            }
            if (params.length != 1) str(")")
            str(" => ")
            normal(ret)
          } else {
            val prettyPre = if (pre == s.NoType) sym.trivialPrefix(env) else pre
            prettyPre match {
              case _: s.SingleType | _: s.ThisType | _: s.SuperType =>
                prefix(prettyPre)
                str(".")
              case s.NoType =>
                ()
              case _ =>
                prefix(prettyPre)
                str("#")
            }
            pprint(sym)
            rep("[", args, ", ", "]")(normal)
          }
        case s.SingleType(pre, sym) =>
          val prettyPre = if (pre == s.NoType) sym.trivialPrefix(env) else pre
          opt(prettyPre, ".")(prefix)
          pprint(sym)
        case s.ThisType(sym) =>
          opt(sym, ".")(pprint)
          str("this")
        case s.WithType(types) =>
          rep(types, " with ") { tpe =>
            // FIXME: https://github.com/twitter/rsc/issues/142
            val needsParens = tpe.isInstanceOf[s.ExistentialType]
            if (needsParens) str("(")
            normal(tpe)
            if (needsParens) str(")")
          }
        case s.StructuralType(utpe, decls) =>
          decls.infos.foreach(index.symbols.append)
          opt(utpe)(normal)
          if (decls.infos.nonEmpty) {
            rep(" { ", decls.infos, "; ", " }")(pprint)
          } else {
            utpe match {
              case s.WithType(tpes) if tpes.length > 1 => ()
              case _ => str(" {}")
            }
          }
        case s.AnnotatedType(anns, utpe) =>
          opt(utpe)(normal)
          str(" ")
          rep(anns, " ", "")(pprint)
        case s.ExistentialType(utpe, decls) =>
          decls.infos.foreach(index.symbols.append)
          opt(utpe)(normal)
          rep(" forSome { ", decls.infos, "; ", " }")(pprint)
        case s.UniversalType(tparams, utpe) =>
          // FIXME: https://github.com/twitter/rsc/issues/150
          str("({ type λ")
          tparams.infos.foreach(index.symbols.append)
          rep("[", tparams.infos, ", ", "] = ")(pprint)
          opt(utpe)(normal)
          str(" })#λ")
        case s.ByNameType(utpe) =>
          str("=> ")
          opt(utpe)(normal)
        case s.RepeatedType(utpe) =>
          opt(utpe)(normal)
          str("*")
        case _: s.SuperType | _: s.ConstantType | _: s.IntersectionType |
            _: s.UnionType | s.NoType =>
          val details = tpe.asMessage.toProtoString
          sys.error(s"unsupported type: $details")
      }
    }
    def normal(tpe: s.Type): Unit = {
      tpe match {
        case _: s.SingleType | _: s.ThisType | _: s.SuperType =>
          prefix(tpe)
          str(".type")
        case _ =>
          prefix(tpe)
      }
    }
    normal(tpe)
  }

  private def pprint(sym: String): Unit = {
    val printableName = {
      val sourceName = index.symbols.get(sym).map(_.name)
      sourceName match {
        case Some(name) =>
          if (name == "") {
            sys.error(s"unsupported symbol: $sym")
          } else if (name == "_" || name.startsWith("?")) {
            gensymCache.getOrElseUpdate(sym, gensym("T"))
          } else {
            name
          }
        case None =>
          if (sym.isGlobal) sym.desc.name
          else sym
      }
    }
    if (keywords.containsKey(printableName)) str("`")
    str(printableName)
    if (keywords.containsKey(printableName)) str("`")
  }

  private def pprint(info: s.SymbolInformation): Unit = {
    if (info.kind == k.METHOD && info.name.endsWith("_=")) return
    index.symbols.append(info)
    rep(info.annotations, " ", " ")(pprint)
    if (info.has(p.COVARIANT)) str("+")
    if (info.has(p.CONTRAVARIANT)) str("-")
    info.kind match {
      case k.METHOD if info.has(p.VAL) => str("val ")
      case k.METHOD if info.has(p.VAR) => str("var ")
      case k.METHOD => str("def ")
      case k.TYPE => str("type ")
      case k.PARAMETER => str("")
      case k.TYPE_PARAMETER => str("")
      case other => sys.error(s"unsupported info: ${info.toProtoString}")
    }
    pprint(info.symbol)
    info.signature match {
      case s.MethodSignature(tparams, paramss, res) =>
        rep("[", tparams.infos, ", ", "]")(pprint)
        rep("(", paramss, ")(", ")") { params =>
          if (params.infos.exists(_.has(p.IMPLICIT))) str("implicit ")
          rep(params.infos, ", ")(pprint)
        }
        opt(": ", res)(pprint)
      case s.TypeSignature(tparams, lo, hi) =>
        rep("[", tparams.infos, ", ", "]")(pprint)
        if (lo != hi) {
          lo match {
            case s.TypeRef(s.NoType, "scala/Nothing#", Nil) => ()
            case lo => opt(" >: ", lo)(pprint)
          }
          hi match {
            case s.TypeRef(s.NoType, "scala/Any#", Nil) => ()
            case hi => opt(" <: ", hi)(pprint)
          }
        } else {
          val alias = lo
          opt(" = ", alias)(pprint)
        }
      case s.ValueSignature(tpe) =>
        str(": ")
        pprint(tpe)
      case other =>
        val details = other.asMessage.toProtoString
        sys.error(s"unsupported signature: $details")
    }
  }

  private def pprint(ann: s.Annotation): Unit = {
    str("@")
    ann.tpe match {
      case s.NoType =>
        sys.error(s"unsupported annotation: ${ann.toProtoString}")
      case tpe =>
        pprint(tpe)
    }
  }

  private val gensymCache = mutable.Map[String, String]()
  private object gensym {
    private val counters = mutable.Map[String, Int]()
    def apply(prefix: String): String = {
      val nextCounter = counters.getOrElse(prefix, 0) + 1
      counters(prefix) = nextCounter
      prefix + nextCounter
    }
  }
}