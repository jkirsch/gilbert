package org.gilbertlang.examples

import org.gilbertlang.language.Gilbert
import eu.stratosphere.client.LocalExecutor
import org.gilbertlang.runtime.withStratosphere

object TypeConversion {

  def main(args:Array[String]){
    val executable = Gilbert.compileRessource("typeConversion.gb")

    withStratosphere(executable).local(4)
  }
}