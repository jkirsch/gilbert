package org.gilbertlang.examples

import org.gilbertlang.language.Gilbert
import eu.stratosphere.client.LocalExecutor
import org.gilbertlang.runtime.withStratosphere

object CellArray {

  def main(args:Array[String]){
    val executable = Gilbert.compileRessource("cellArray.gb")

    val plan = withStratosphere(executable);
    LocalExecutor.execute(plan)
  }
}