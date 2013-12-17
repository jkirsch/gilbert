/**
 * gilbert - Distributed Linear Algebra on Sparse Matrices
 * Copyright (C) 2013  Sebastian Schelter, Till Rohrmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.gilbertlang.language.definition

import org.gilbertlang.language.definition.Values._

trait ValuePrinter {
  
  def prettyString(value: Value): String = {

    value match {
      case UndefinedValue => "Undefined"
      case IntValue(value) => value.toString
      case ReferenceValue(ref) => "$" + ref
      case UniversalValue(valueVar) => "∀" + prettyString(valueVar)
      case ValueVar(id) => "φ(" + id +")"
    }
  }

}