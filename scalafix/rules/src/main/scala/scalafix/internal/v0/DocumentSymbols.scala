// Copyright (c) 2017-2018 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package scalafix.internal.v0

import scala.collection.mutable
import scala.meta.internal.{semanticdb => s}

case class DocumentSymbols(doc: s.TextDocument) {
  private val map = mutable.Map[String, s.SymbolInformation]()
  doc.symbols.foreach(append)
  def append(info: s.SymbolInformation): Unit = map(info.symbol) = info
  def apply(sym: String): s.SymbolInformation = map(sym)
  def get(sym: String): Option[s.SymbolInformation] = map.get(sym)
}
