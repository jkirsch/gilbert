/**
 * gilbert - Distributed Linear Algebra on Sparse Matrices
 * Copyright (C) 2013  Sebastian Schelter
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

package org.gilbertlang.runtime.optimization

import org.gilbertlang.runtime.Executables._

abstract class DagWalker {

  private var iteration = 0

  def currentIteration() = iteration

  def onArrival(transformation: Executable) = {}
  def onLeave(transformation: Executable) = {}

  def visit(transformation: Executable): Unit = {

    transformation match {

      case x: Parameter =>
        onArrival(x)
        onLeave(x)

      case (transformation: LoadMatrix) =>
        onArrival(transformation)
        visit(transformation.path)
        visit(transformation.numRows)
        visit(transformation.numColumns)
        onLeave(transformation)

      case (transformation: FixpointIterationMatrix) =>
        iteration += 1

        onArrival(transformation)
        visit(transformation.initialState)
        visit(transformation.updatePlan)
        if(transformation.convergencePlan != null){
          visit(transformation.convergencePlan)
        }
        onLeave(transformation)

        iteration -= 1

      case (transformation: FixpointIterationCellArray) =>
        iteration += 1

        onArrival(transformation)
        visit(transformation.initialState)
        visit(transformation.updatePlan)
        if(transformation.convergencePlan != null){
          visit(transformation.convergencePlan)
        }
        onLeave(transformation)

        iteration -= 1

      case IterationStatePlaceholder =>
        onArrival(IterationStatePlaceholder)
        onLeave(IterationStatePlaceholder)

      case transformation: IterationStatePlaceholderCellArray =>
        onArrival(transformation)
        onLeave(transformation)

      case ConvergenceCurrentStatePlaceholder =>
        onArrival(ConvergenceCurrentStatePlaceholder)
        onLeave(ConvergenceCurrentStatePlaceholder)

      case ConvergencePreviousStatePlaceholder =>
        onArrival(ConvergencePreviousStatePlaceholder)
        onLeave(ConvergencePreviousStatePlaceholder)

      case placeholder: ConvergenceCurrentStateCellArrayPlaceholder =>
        onArrival(placeholder)
        onLeave(placeholder)

      case placeholder: ConvergencePreviousStateCellArrayPlaceholder =>
        onArrival(placeholder)
        onLeave(placeholder)

      case (transformation: CellwiseMatrixTransformation) =>
        onArrival(transformation)
        visit(transformation.matrix)
        onLeave(transformation)

      case (transformation: CellwiseMatrixMatrixTransformation) =>
        onArrival(transformation)
        visit(transformation.left)
        visit(transformation.right)
        onLeave(transformation)

      case (transformation: Transpose) =>
        onArrival(transformation)
        visit(transformation.matrix)
        onLeave(transformation)

      case (transformation: MatrixMult) =>
        onArrival(transformation)
        visit(transformation.left)
        visit(transformation.right)
        onLeave(transformation)

      case (transformation: AggregateMatrixTransformation) =>
        onArrival(transformation)
        visit(transformation.matrix)
        onLeave(transformation)

      case (transformation: ScalarMatrixTransformation) =>
        onArrival(transformation)
        visit(transformation.matrix)
        visit(transformation.scalar)
        onLeave(transformation)

      case transformation: MatrixScalarTransformation =>
        onArrival(transformation)
        visit(transformation.matrix)
        visit(transformation.scalar)
        onLeave(transformation)

      case (transformation: VectorwiseMatrixTransformation) =>
        onArrival(transformation)
        visit(transformation.matrix)
        onLeave(transformation)

      case (transformation: ones) =>
        onArrival(transformation)
        visit(transformation.numRows)
        visit(transformation.numColumns)
        onLeave(transformation)

      case (transformation: randn) =>
        onArrival(transformation)
        visit(transformation.numRows)
        visit(transformation.numColumns)
        visit(transformation.mean)
        visit(transformation.std)
        onLeave(transformation)

      case (transformation: urand) =>
        onArrival(transformation)
        visit(transformation.numRows)
        visit(transformation.numColumns)
        onLeave(transformation)

      case transformation: sprand =>
        onArrival(transformation)
        visit(transformation.numRows)
        visit(transformation.numCols)
        visit(transformation.mean)
        visit(transformation.std)
        visit(transformation.level)
        onLeave(transformation)

      case transformation: adaptiveRand =>
        onArrival(transformation)
        visit(transformation.numRows)
        visit(transformation.numColumns)
        visit(transformation.mean)
        visit(transformation.std)
        visit(transformation.level)
        onLeave(transformation)

      case transformation: spones =>
        onArrival(transformation)
        visit(transformation.matrix)
        onLeave(transformation)

      case transformation: sum =>
        onArrival(transformation)
        visit(transformation.matrix)
        visit(transformation.dimension)
        onLeave(transformation)

      case transformation: sumRow =>
        onArrival(transformation)
        visit(transformation.matrix)
        onLeave(transformation)

      case transformation: sumCol =>
        onArrival(transformation)
        visit(transformation.matrix)
        onLeave(transformation)

      case transformation: diag =>
        onArrival(transformation)
        visit(transformation.matrix)
        onLeave(transformation)

      case (transformation: WriteMatrix) =>
        onArrival(transformation)
        visit(transformation.matrix)
        onLeave(transformation)

      case (transformation: scalar) =>
        onArrival(transformation)
        onLeave(transformation)

      case transformation: string =>
        onArrival(transformation)
        onLeave(transformation)

      case transformation: boolean =>
        onArrival(transformation)
        onLeave(transformation)

      case (transformation: WriteScalar) =>
        onArrival(transformation)
        visit(transformation.scalar)
        onLeave(transformation)

      case transformation: WriteString =>
        onArrival(transformation)
        visit(transformation.string)
        onLeave(transformation)

      case transformation: WriteFunction =>
        onArrival(transformation)
        visit(transformation.function)
        onLeave(transformation)

      case transformation: WriteCellArray =>
        onArrival(transformation)
        visit(transformation.cellArray)
        onLeave(transformation)

      case transformation: CompoundExecutable =>
        onArrival(transformation)
        transformation.executables foreach { visit }
        onLeave(transformation)

      case transformation: ScalarScalarTransformation =>
        onArrival(transformation)
        visit(transformation.left)
        visit(transformation.right)
        onLeave(transformation)

      case transformation: UnaryScalarTransformation =>
        onArrival(transformation)
        visit(transformation.scalar)
        onLeave(transformation)

      case func: function =>
        onArrival(func)
        visit(func.body)
        onLeave(func)

      case VoidExecutable =>
        onArrival(VoidExecutable)
        onLeave(VoidExecutable)

      case transformation: zeros =>
        onArrival(transformation)
        visit(transformation.numRows)
        visit(transformation.numCols)
        onLeave(transformation)

      case transformation: eye =>
        onArrival(transformation)
        visit(transformation.numRows)
        visit(transformation.numCols)
        onLeave(transformation)

      case transformation: CellArrayReference =>
        onArrival(transformation)
        visit(transformation.parent)
        onLeave(transformation)

      case transformation: CellArrayExecutable =>
        onArrival(transformation)
        transformation.elements foreach { visit(_) }
        onLeave(transformation)

      case typeConversion: TypeConversionScalar =>
        onArrival(typeConversion)
        visit(typeConversion.scalar)
        onLeave(typeConversion)

      case typeConversion: TypeConversionMatrix =>
        onArrival(typeConversion)
        visit(typeConversion.matrix)
        onLeave(typeConversion)

      case repmat: repmat =>
        onArrival(repmat)
        visit(repmat.matrix)
        visit(repmat.numRows)
        visit(repmat.numCols)
        onLeave(repmat)

      case linspace: linspace =>
        onArrival(linspace)
        visit(linspace.start)
        visit(linspace.end)
        visit(linspace.numPoints)
        onLeave(linspace)

      case pdist2: pdist2 =>
        onArrival(pdist2)
        visit(pdist2.matrixA)
        visit(pdist2.matrixB)
        onLeave(pdist2)

      case minWithIndex: minWithIndex =>
        onArrival(minWithIndex)
        visit(minWithIndex.matrix)
        visit(minWithIndex.dimension)
        onLeave(minWithIndex)
    }
  }

}
