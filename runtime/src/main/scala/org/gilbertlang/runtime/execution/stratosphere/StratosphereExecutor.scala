package org.gilbertlang.runtime.execution.stratosphere

import org.gilbertlang.runtime.Executor
import eu.stratosphere.api.scala.operators.CsvInputFormat
import eu.stratosphere.api.scala.DataSource
import eu.stratosphere.api.scala.LiteralDataSource
import eu.stratosphere.api.scala.DataSet
import eu.stratosphere.api.scala.operators.CsvOutputFormat
import eu.stratosphere.api.scala.io.LiteralInputFormat
import eu.stratosphere.api.scala.operators.DelimitedOutputFormat
import scala.collection.convert.WrapAsScala
import scala.collection.mutable.ArrayBuilder
import eu.stratosphere.api.scala.ScalaPlan
import eu.stratosphere.api.scala.ScalaSink
import org.gilbertlang.runtime.Operations._
import org.gilbertlang.runtime.Executables._
import org.gilbertlang.runtimeMacros.linalg.Submatrix
import org.gilbertlang.runtimeMacros.linalg.SubmatrixBoolean
import org.gilbertlang.runtimeMacros.linalg.Partition
import org.gilbertlang.runtimeMacros.linalg.numerics
import org.gilbertlang.runtime.execution.CellwiseFunctions
import breeze.linalg.norm
import breeze.linalg.*
import org.gilbertlang.runtimeMacros.linalg.Subvector
import org.gilbertlang.runtimeMacros.linalg.SquareBlockPartitionPlan
import org.gilbertlang.runtimeMacros.linalg.Configuration
import eu.stratosphere.api.common.operators.Operator
import scala.language.implicitConversions
import scala.language.reflectiveCalls
import org.gilbertlang.runtime.RuntimeTypes.{BooleanType, DoubleType}
import scala.collection.mutable

class StratosphereExecutor extends Executor with WrapAsScala {
  type Matrix = DataSet[Submatrix]
  type BooleanMatrix = DataSet[SubmatrixBoolean]
  type Scalar[T] = DataSet[T]
  private var tempFileCounter = 0
  private var iterationStatePlaceholderValue: Option[Matrix] = None
  private val matrixRegistry = mutable.HashMap[Int, Matrix]()

  
  def getCWD: String = System.getProperty("user.dir")

  def newTempFileName(): String = {
    tempFileCounter += 1
    "file://" + getCWD + "/gilbert" + tempFileCounter + ".output"
  }

  def registerValue(index: Int, value: Matrix){
    matrixRegistry.put(index, value)
  }

  def getRegisteredValue(index: Int):Option[Matrix] = {
    matrixRegistry.get(index)
  }
  
  implicit def dataset2Operator[T](dataset: DataSet[T]): Operator = dataset.contract

  def execute(executable: Executable): Any = {
    executable match {

      case executable: WriteMatrix => {
        handle[WriteMatrix, Matrix](
          executable,
          { exec => evaluate[Matrix](exec.matrix) },
          { (_, matrix) =>
            {
              val completePathWithFilename = newTempFileName()
              matrix.write(completePathWithFilename, DelimitedOutputFormat(Submatrix.outputFormatter("\n", " "), ""), 
                  s"WriteMatrix($completePathWithFilename)");
            }
          })
      }

      case executable: WriteString => {
        handle[WriteString, Scalar[String]](
          executable,
          { exec => evaluate[Scalar[String]](exec.string) },
          { (_, string) =>
            {
              val completePathWithFilename = newTempFileName()
              string.write(completePathWithFilename, CsvOutputFormat(), s"WriteString($completePathWithFilename)")
            }
          })
      }

      //TODO: Fix
      case executable: WriteFunction => { 
        throw new TransformationNotSupportedError("WriteFunction is not supported by Stratosphere")
      }

      //TODO: Fix
      case executable: WriteScalar => {
        executable.scalar.getType match {
          case DoubleType =>
            handle[WriteScalar, Scalar[Double]](
            executable,
            { exec => evaluate[Scalar[Double]](exec.scalar) },
            { (_, scalar) =>
            {
              val completePathWithFilename = newTempFileName()
              scalar.write(completePathWithFilename, CsvOutputFormat(), s"WriteScalarRef($completePathWithFilename)")
            }
            })
          case BooleanType =>
            handle[WriteScalar, Scalar[Boolean]](
            executable,
            { exec => evaluate[Scalar[Boolean]](exec.scalar) },
            { (_, scalar) =>
              val completePathWithFilename = newTempFileName()
              scalar.write(completePathWithFilename, CsvOutputFormat(), s"WriteScalarRef($completePathWithFilename)")
            }
            )

        }


      }

      case VoidExecutable => {
        null
      }

      case executable: ScalarMatrixTransformation => {
        executable.operation match {
          case logicOperation: LogicOperation => {
            handle[ScalarMatrixTransformation, (Scalar[Boolean], BooleanMatrix)](
                executable,
                { exec => (evaluate[Scalar[Boolean]](exec.scalar), evaluate[BooleanMatrix](exec.matrix))},
                { case (_, (scalar, matrix)) =>{
                  logicOperation match {
                    case And => {
                      val result = scalar cross matrix map { (scalar, submatrix) => submatrix :& scalar }
                      result.setName("SM: Logical And")
                      result
                    }
                    case Or => {
                      val result = scalar cross matrix map { (scalar, submatrix) => submatrix :| scalar }
                      result.setName("SM: Logical Or")
                      result
                    }
                  }
                }})
          }
          case operation => {
            handle[ScalarMatrixTransformation, (Scalar[Double], Matrix)](
          executable,
          { exec => (evaluate[Scalar[Double]](exec.scalar), evaluate[Matrix](exec.matrix)) },
          {
            case (_, (scalar, matrix)) => {
              operation match {
                case Addition => {
                  val result = scalar cross matrix map { (scalar, submatrix) => submatrix + scalar }
                  result.setName("SM: Addition")
                  result
                }
                case Subtraction => {
                  val result = scalar cross matrix map { (scalar, submatrix) => submatrix + -scalar }
                  result.setName("SM: Subtraction")
                  result
                }
                case Multiplication => {
                  val result = scalar cross matrix map { (scalar, submatrix) => submatrix * scalar }
                  result.setName("SM: Multiplication")
                  result
                }
                case Division => {
                  val result = scalar cross matrix map { (scalar, submatrix) =>
                    {
                      val partition = submatrix.getPartition
                      val result = Submatrix.init(partition, scalar)
                      result / submatrix
                    }
                  }
                  result.setName("SM: Division")
                  result
                }
                case GreaterThan => {
                  val result = scalar cross matrix map { (scalar, submatrix) => submatrix :< scalar }
                  result.setName("SM: Greater than")
                  result
                }
                case GreaterEqualThan => {
                  val result = scalar cross matrix map { (scalar, submatrix) => submatrix :<= scalar }
                  result.setName("SM: Greater equal than")
                  result
                }
                case LessThan => {
                  val result = scalar cross matrix map { (scalar, submatrix) => submatrix :> scalar }
                  result.setName("SM: Less than")
                  result
                }
                case LessEqualThan => {
                  val result = scalar cross matrix map { (scalar, submatrix) => submatrix :>= scalar }
                  result.setName("SM: Less equal than")
                  result
                }
                case Equals => {
                  val result = scalar cross matrix map { (scalar, submatrix) => submatrix :== scalar }
                  result.setName("SM: Equals")
                  result
                }
                case NotEquals => {
                  val result = scalar cross matrix map { (scalar, submatrix) => submatrix :!= scalar }
                  result.setName("SM: Not equals")
                  result
                } 
              }
            }
          })
          }
        }
      }

      case executable: MatrixScalarTransformation => {
        executable.operation match {
          case logicOperation: LogicOperation => {
            handle[MatrixScalarTransformation, (BooleanMatrix, Scalar[Boolean])](
                executable,
                {exec => (evaluate[BooleanMatrix](exec.matrix), evaluate[Scalar[Boolean]](exec.scalar))},
                { case (_, (matrix, scalar)) => {
                  logicOperation match {
                    case And => {
                      val result = matrix cross scalar map { (submatrix, scalar) => submatrix :& scalar }
                      result.setName("MS: Logical And")
                      result
                    }
                    case Or => {
                      val result = matrix cross scalar map { (submatrix, scalar) => submatrix :| scalar }
                      result.setName("MS: Logical Or")
                      result
                    } 
                  }
                } 
                })
          }
          case operation => {
              handle[MatrixScalarTransformation, (Matrix, Scalar[Double])](
          executable,
          { exec => (evaluate[Matrix](exec.matrix), evaluate[Scalar[Double]](exec.scalar)) },
          {
            case (_, (matrix, scalar)) => {
              operation match {
                case Addition => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix + scalar }
                  result.setName("MS: Addition")
                  result
                }
                case Subtraction => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix - scalar }
                  result.setName("MS: Subtraction")
                  result
                }
                case Multiplication => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix * scalar }
                  result.setName("MS: Multiplication")
                  result
                }
                case Division => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix / scalar }
                  result.setName("MS: Division")
                  result
                }
                case GreaterThan => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix :> scalar }
                  result.setName("MS: Greater than")
                  result
                }
                case GreaterEqualThan => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix :>= scalar }
                  result.setName("MS: Greater equal than")
                  result
                }
                case LessThan => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix :< scalar }
                  result.setName("MS: Less than")
                  result
                }
                case LessEqualThan => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix :<= scalar }
                  result.setName("MS: Less equal than")
                  result
                }
                case Equals => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix :== scalar }
                  result.setName("MS: Equals")
                  result
                }
                case NotEquals => {
                  val result = matrix cross scalar map { (submatrix, scalar) => submatrix :!= scalar }
                  result.setName("MS: Not equals")
                  result
                }
              }
            }
          })
          }
        }
      
      }

      case executable: ScalarScalarTransformation => {

        executable.operation match {
          case logicOperation: LogicOperation =>
            handle[ScalarScalarTransformation, (Scalar[Boolean], Scalar[Boolean])](
              executable,
              {exec => (evaluate[Scalar[Boolean]](exec.left), evaluate[Scalar[Boolean]](exec.right))},
              {case (_, (left, right)) => 
                logicOperation match {
                  case And => { 
                    val result = left cross right map { (left, right) => left && right }
                    result.setName("SS: Logical And")
                    result
                  }
                  case Or => { 
                    val result = left cross right map { (left, right) => left || right }
                    result.setName("SS: Logical Or")
                    result
                  }
                }
              }
            )
          case operation =>
            handle[ScalarScalarTransformation, (Scalar[Double], Scalar[Double])](
              executable,
              { exec => (evaluate[Scalar[Double]](exec.left), evaluate[Scalar[Double]](exec.right)) },
              {
                case (_, (left, right)) => {
                  operation match {
                    case Addition => {
                      val result = left cross right map { (left, right) => left + right }
                      result.setName("SS: Addition")
                      result
                    }
                    case Subtraction => {
                      val result = left cross right map { (left, right) => left - right }
                      result.setName("SS: Subtraction")
                      result
                    }
                    case Multiplication => {
                      val result = left cross right map { (left, right) => left * right }
                      result.setName("SS: Multiplication")
                      result
                    }
                    case Division => {
                      val result =left cross right map { (left, right) => left / right }
                      result.setName("SS: Division")
                      result
                    }
                    case Maximum => {
                      val result = left union right combinableReduceAll { elements => elements.max }
                      result.setName("SS: Maximum")
                      result
                    }
                    case Minimum => {
                      val result = left union right combinableReduceAll { elements => elements.min }
                      result.setName("SS: Minimum")
                      result
                    }
                    case GreaterThan => {
                      val result = left cross right map { (left, right) => left > right }
                      result.setName("SS: Greater than")
                      result
                    }
                    case GreaterEqualThan => {
                      val result = left cross right map { (left, right) => left >= right }
                      result.setName("SS: Greater equal than")
                      result
                    }
                    case LessThan => {
                      val result = left cross right map { (left, right) => left < right }
                      result.setName("SS: Less than")
                      result
                    }
                    case LessEqualThan => {
                      val result = left cross right map { (left, right) => left <= right }
                      result.setName("SS: Less equal than")
                      result
                    }
                    case Equals => {
                      val result = left cross right map { (left, right) => left == right}
                      result.setName("SS: Equals")
                      result
                    }
                    case NotEquals => {
                      val result = left cross right map { (left, right) => left != right }
                      result.setName("SS: Not equals")
                      result
                    }
                  }
                }
              })
        }
      }

      case executable: AggregateMatrixTransformation => {
        handle[AggregateMatrixTransformation, Matrix](
          executable,
          { exec => evaluate[Matrix](exec.matrix) },
          { (exec, matrix) =>
            {
              exec.operation match {
                case Maximum => {
                  matrix map { x => x.max } combinableReduceAll
                    { elements => elements.max }
                }
                case Minimum => {
                  matrix map { x => x.min } combinableReduceAll
                    { elements => elements.min }
                }
                case Norm2 => {
                  matrix map { x => breeze.linalg.sum(x:*x) } combinableReduceAll
                    { x => x.fold(0.0)(_ + _) } map
                    { x => math.sqrt(x) }
                }
              }
            }
          })
      }

      case executable: UnaryScalarTransformation => {
        handle[UnaryScalarTransformation, Scalar[Double]](
          executable,
          { exec => evaluate[Scalar[Double]](exec.scalar) },
          { (exec, scalar) =>
            {
              exec.operation match {
                case Minus => {
                  scalar map { x => -x }
                }
                case Binarize => {
                  scalar map { x => CellwiseFunctions.binarize(x) }
                }
              }
            }
          })
      }

      case executable: scalar => {
        handle[scalar, Unit](
          executable,
          { _ => },
          { (exec, _) => LiteralDataSource(exec.value, LiteralInputFormat[Double]()) })
      }
      
      case executable: boolean => {
        handle[boolean, Unit](
            executable,
            {_ => },
            { (exec, _) => LiteralDataSource(exec.value, LiteralInputFormat[Boolean]())})
      }

      case executable: string => {
        handle[string, Unit](
          executable,
          { _ => },
          { (exec, _) => LiteralDataSource(exec.value, LiteralInputFormat[String]()) })
      }
  
      case executable: CellwiseMatrixTransformation => {
        handle[CellwiseMatrixTransformation, Matrix](
          executable,
          { exec => evaluate[Matrix](exec.matrix) },
          { (exec, matrix) =>
            {
              exec.operation match {
                case Minus => {
                  matrix map { submatrix => submatrix * -1.0}
                }
                case Binarize => {
                  matrix map { submatrix =>
                    {
                      submatrix.mapActiveValues(x => CellwiseFunctions.binarize(x))
                    }
                  }
                }
              }
            }
          })
      }
      
      case executable: CellwiseMatrixMatrixTransformation =>
        {
          executable.operation match {
            case logicOperation: LogicOperation => {
              handle[CellwiseMatrixMatrixTransformation, (BooleanMatrix, BooleanMatrix)](
                  executable,
                  { exec => (evaluate[BooleanMatrix](exec.left), evaluate[BooleanMatrix](exec.right))},
                  { case (_, (left, right)) => {
                    logicOperation match {
                      case And => {
                        val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                        { y => (y.rowIndex, y.columnIndex) } map 
                        { (left, right) => left :& right }
                        result.setName("MM: Logical And")
                        result
                      }
                      case Or => {
                        val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                        { y => (y.rowIndex, y.columnIndex) } map 
                        { (left, right) => left :| right }
                        result.setName("MM: Logical Or")
                        result
                      }
                    }
                  }
                  })
            }
            case operation => {
               handle[CellwiseMatrixMatrixTransformation, (Matrix, Matrix)](
            executable,
            { exec => (evaluate[Matrix](exec.left), evaluate[Matrix](exec.right)) },
            {
              case (_ , (left, right)) => {
                operation match {
                  case Addition => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                      { y => (y.rowIndex, y.columnIndex) } map
                      { (left, right) => left + right }
                    result.setName("MM: Addition")
                    result
                  }
                  case Subtraction => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                      { y => (y.rowIndex, y.columnIndex) } map
                      { (left, right) => left - right }
                    result.setName("MM: Subtraction")
                    result
                  }
                  case Multiplication => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                      { y => (y.rowIndex, y.columnIndex) } map
                      { (left, right) => left :* right
                      }
                    result.setName("MM: Cellwise multiplication")
                    result
                  }
                  case Division => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                      { y => (y.rowIndex, y.columnIndex) } map
                      { (left, right) => left / right
                      }
                    result.setName("MM: Division")
                    result
                  }
                  case Maximum => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                      { y => (y.rowIndex, y.columnIndex) } map
                      { (left, right) =>
                       numerics.max(left, right)
                      }
                    result.setName("MM: Maximum")
                    result
                  }
                  case Minimum => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                      { y => (y.rowIndex, y.columnIndex) } map
                      { (left, right) =>
                        numerics.min(left, right)
                      }
                    result.setName("MM: Minimum")
                    result
                  }
                  case GreaterThan => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                    { y => (y.rowIndex, y.columnIndex) } map 
                    { (left,right) => left :> right }
                    result.setName("MM: Greater than")
                    result
                  }
                  case GreaterEqualThan => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                    {y => (y.rowIndex, y.columnIndex) } map 
                    { (left, right) => left :>= right }
                    result.setName("MM: Greater equal than")
                    result
                  }
                  case LessThan => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                    { y => (y.rowIndex, y.columnIndex) } map
                    { (left, right) => left :< right }
                    result.setName("MM: Less than")
                    result
                  }
                  case LessEqualThan => {
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                    { y => (y.rowIndex, y.columnIndex) } map
                    { (left, right) => left :<= right }
                    result.setName("MM: Less equal than")
                    result
                  }
                  case Equals =>
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                    { y => (y.rowIndex, y.columnIndex) } map
                    { (left, right) => left :== right }
                    result.setName("MM: Equals")
                    result
                  case NotEquals => { 
                    val result = left join right where { x => (x.rowIndex, x.columnIndex) } isEqualTo
                    { y => (y.rowIndex, y.columnIndex) } map
                    { (left, right) => left :!= right }
                    result.setName("MM: NotEquals")
                    result
                  }
                }
              }
            })
            }
          }
         
        }

      case executable: MatrixMult => {
        handle[MatrixMult, (Matrix, Matrix)](
          executable,
          { exec => (evaluate[Matrix](exec.left), evaluate[Matrix](exec.right)) },
          {
            case (_, (left, right)) => {
              left join right where { leftElement => leftElement.columnIndex } isEqualTo
                { rightElement => rightElement.rowIndex } map
                { (left, right) => left * right } groupBy
                { element => (element.rowIndex, element.columnIndex) } combinableReduceGroup
                { elements =>
                  {
                    val element = elements.next.copy
                    elements.foldLeft(element)({ _ + _ })
                  }
                }
            }
          })
      }
      
      case executable: Transpose => {
        handle[Transpose, Matrix](
          executable,
          { exec => evaluate[Matrix](exec.matrix) },
          { (_, matrix) =>
            {
              matrix map {
                case Submatrix(matrix, rowIdx, columnIdx, rowOffset, columnOffset, numTotalRows, numTotalColumns) =>
                  Submatrix(matrix.t, columnIdx, rowIdx, columnOffset, rowOffset, numTotalColumns,
                    numTotalRows)
              }
            }
          })
      }

      case executable: VectorwiseMatrixTransformation => {
        handle[VectorwiseMatrixTransformation, Matrix](
          executable,
          { exec => evaluate[Matrix](exec.matrix) },
          { (exec, matrix) =>
            {
              exec.operation match {
                case NormalizeL1 => {
                  matrix map { submatrix =>
                    norm(submatrix( * , ::),1)
                  } groupBy (subvector => subvector.index) combinableReduceGroup {
                    subvectors =>
                      {
                        val firstElement = subvectors.next.copy
                        subvectors.foldLeft(firstElement)(_ + _)
                      }
                  } join
                    matrix where { l1norm => l1norm.index } isEqualTo { submatrix => submatrix.rowIndex } map
                    { (l1norm, submatrix) =>
                      val result = submatrix.copy
                      for(col <- 0 until submatrix.cols)
                        result(::, col) :/= l1norm
                        
                      result
                    }
                }
                case Maximum => {
                  matrix map { submatrix => numerics.max(submatrix(*, ::)) } groupBy
                    { subvector => subvector.index } combinableReduceGroup { subvectors =>
                      val firstElement = subvectors.next.copy
                      subvectors.foldLeft(firstElement) { numerics.max(_, _) }
                    } map { subvector => subvector.asMatrix }
                }
                case Minimum => {
                  matrix map { submatrix => numerics.min(submatrix(*, ::)) } groupBy
                    { subvector => subvector.index } combinableReduceGroup { subvectors =>
                      val firstElement = subvectors.next.copy
                      subvectors.foldLeft(firstElement) { numerics.min(_, _) }
                    } map { subvector => subvector.asMatrix }
                }
                case Norm2 => {
                  matrix map { submatrix =>
                    norm(submatrix( *, ::),2)
                  } groupBy { subvector => subvector.index } combinableReduceGroup { subvectors =>
                    val firstElement = subvectors.next.copy
                    subvectors.foldLeft(firstElement) { _ + _ }
                  } map { subvector => subvector.asMatrix  }
                }

              }
            }
          })
      }

      case executable: LoadMatrix => {
        handle[LoadMatrix, (Scalar[String], Scalar[Double], Scalar[Double])](
          executable,
          { exec =>
            (evaluate[Scalar[String]](exec.path), evaluate[Scalar[Double]](exec.numRows),
              evaluate[Scalar[Double]](exec.numColumns))
          },
          {
            case (_, (path, rows, cols)) => {
              if (!path.contract.isInstanceOf[LiteralDataSource[String]]) {
                throw new IllegalArgumentError("Path for LoadMatrix has to be a literal.")
              }

              if (!rows.contract.isInstanceOf[LiteralDataSource[Double]]) {
                throw new IllegalArgumentError("Rows for LoadMatrix has to be a literal.")
              }

              if (!cols.contract.isInstanceOf[LiteralDataSource[Double]]) {
                throw new IllegalArgumentError("Cols for LoadMatrix has to be a literal.")
              }

              val pathLiteral = path.contract.asInstanceOf[LiteralDataSource[String]].values.head
              val rowLiteral = rows.contract.asInstanceOf[LiteralDataSource[Double]].values.head.toInt
              val columnLiteral = cols.contract.asInstanceOf[LiteralDataSource[Double]].values.head.toInt

              val source = DataSource("file://" + pathLiteral, CsvInputFormat[(Int, Int, Double)]("\n", ' '))
              
              val partitionPlan = new SquareBlockPartitionPlan(Configuration.BLOCKSIZE, rowLiteral, columnLiteral)
             
              val blocks = LiteralDataSource(0, LiteralInputFormat[Int]()) flatMap { _ =>
                for (partition <- partitionPlan.iterator) yield {
                  (partition.id, partition)
                }
              }
              source map {
                case (row, column, value) =>
                  (partitionPlan.partitionId(row-1, column-1), row-1, column-1, value)
              } cogroup blocks where { entry => entry._1 } isEqualTo { block => block._1 } map { (entries, blocks) =>
                if(!blocks.hasNext){
                  throw new IllegalArgumentError("LoadMatrix coGroup phase must have at least one block")
                }
                
                val partition = blocks.next._2
                
                if (blocks.hasNext) {
                  throw new IllegalArgumentError("LoadMatrix coGroup phase must have at most one block")
                }
                
                
                Submatrix(partition, (entries map { case (id, row, col, value) => (row, col, value)}).toSeq)
              }
            }
          })
      }
  
      case compound: CompoundExecutable => {
        val executables = compound.executables map { evaluate[ScalaSink[_]](_) }
        new ScalaPlan(executables)
      }

      
      case executable: ones => {
        handle[ones, (Scalar[Double], Scalar[Double])](
          executable,
          { exec => (evaluate[Scalar[Double]](exec.numRows), evaluate[Scalar[Double]](exec.numColumns)) },
          {
            case (_, (rows, columns)) => {
              val result = rows cross columns flatMap { (rows, columns) =>
                val partitionPlan = new SquareBlockPartitionPlan(Configuration.BLOCKSIZE, rows.toInt, columns.toInt)

                for (matrixPartition <- partitionPlan.iterator) yield {
                  Submatrix.init(matrixPartition, 1.0)
                }
              }
              
              result.setName("Ones")
              result
            }
          })
      }
      
      case executable: zeros => {
        handle[zeros, (Scalar[Double], Scalar[Double])](
            executable,
            { exec => (evaluate[Scalar[Double]](exec.numRows), evaluate[Scalar[Double]](exec.numCols))},
            { case (_, (rows, columns)) => {
              val result = rows cross columns flatMap { (rows, columns) => 
                val partitionPlan = new SquareBlockPartitionPlan(Configuration.BLOCKSIZE, rows.toInt, columns.toInt)
                
                for(matrixPartition <- partitionPlan.iterator) yield {
                  Submatrix(matrixPartition)
                }
              }
              
              result.setName(s"Zeros")
              result
            }})
      }
      
      case executable: eye => {
        handle[eye, (Scalar[Double], Scalar[Double])](
            executable,
            { exec => (evaluate[Scalar[Double]](exec.numRows), evaluate[Scalar[Double]](exec.numCols))},
            { case (_, (rows, columns)) => {
              val result = rows cross columns flatMap { (rows, columns) => 
                val partitionPlan = new SquareBlockPartitionPlan(Configuration.BLOCKSIZE, rows.toInt, columns.toInt)
                
                for(matrixPartition <- partitionPlan.iterator) yield {
                  if(matrixPartition.rowIndex == matrixPartition.columnIndex){
                   Submatrix.eye(matrixPartition)
                  }else{
                    Submatrix(matrixPartition)
                  }
                }
              }
              result.setName("Eye")
              result
            }})
      }

      case executable: randn => {
        handle[randn, (Scalar[Double], Scalar[Double], Scalar[Double], Scalar[Double])](
          executable,
          { exec =>
            (evaluate[Scalar[Double]](exec.numRows), evaluate[Scalar[Double]](exec.numColumns),
              evaluate[Scalar[Double]](exec.mean), evaluate[Scalar[Double]](exec.std))
          },
          {
            case (_, (rows, cols, mean, std)) => {
              val rowsCols = rows cross cols map { (rows, cols) => (rows, cols) } 
              rowsCols.setName("Randn: Rows and cols combined")
              
              val rowsColsMean = rowsCols cross mean map { case ((rows, cols), mean) => (rows, cols, mean) } 
              rowsColsMean.setName("Randn: Rows, cols and mean combined")
              
              val randomPartitions = rowsColsMean cross std flatMap
                {
                  case ((rows, cols, mean), std) =>
                    val partitionPlan = new SquareBlockPartitionPlan(Configuration.BLOCKSIZE, rows.toInt, cols.toInt)

                    val result = for (partition <- partitionPlan.iterator) yield {
                      Submatrix.rand(partition, new GaussianRandom(mean, std))
                    }
                    
                    result
                }
              
              randomPartitions.setName("Randn: Random submatrices")
              randomPartitions
            }
          })
      }

      case executable: spones => {
        handle[spones, Matrix](
          executable,
          { exec => evaluate[Matrix](exec.matrix) },
          { (_, matrix) =>
            {
              matrix map { submatrix =>
                submatrix.mapActiveValues(x => CellwiseFunctions.binarize(x))
              }
            }
          })
      }

      case executable: sumRow => {
        handle[sumRow, Matrix](
          executable,
          { exec => evaluate[Matrix](exec.matrix) },
          { (_, matrix) =>
            {
              matrix map { submatrix => breeze.linalg.sum(submatrix(*, ::)) } groupBy
                { subvector => subvector.index } combinableReduceGroup
                { subvectors =>
                  val firstSubvector = subvectors.next.copy
                  subvectors.foldLeft(firstSubvector)(_ + _)
                } map
                { subvector => subvector.asMatrix }
            }
          })
      }

      case executable: sumCol => {
        handle[sumCol, Matrix](
          executable,
          { exec => evaluate[Matrix](exec.matrix) },
          { (_, matrix) =>
            {
              matrix map { submatrix => breeze.linalg.sum(submatrix(::, *)) } groupBy
                { submatrix => submatrix.columnIndex } combinableReduceGroup
                { subvectors =>
                  val firstSubvector = subvectors.next.copy
                  subvectors.foldLeft(firstSubvector)(_ + _)
                }
            }
          })
      }

      case executable: diag => {
        handle[diag, Matrix](
          executable,
          { exec => evaluate[Matrix](exec.matrix) },
          { (exec, matrix) =>
            {
              (exec.matrix.rows, exec.matrix.cols) match {
                case (Some(1), _) => {
                  val entries = matrix map
                    { submatrix => (submatrix.columnIndex, submatrix.cols, submatrix.columnOffset) }
                  entries cross matrix map {
                    case ((rowIndex, numRows, rowOffset), submatrix) =>
                      val partition = Partition(-1, rowIndex, submatrix.columnIndex, numRows, submatrix.cols,
                        rowOffset, submatrix.columnOffset, submatrix.totalColumns, submatrix.totalColumns)
                      val result = Submatrix(partition, submatrix.totalColumns)

                      if (submatrix.columnIndex == rowIndex) {
                        for (index <- 0 until submatrix.cols) {
                          result.update(index, index, submatrix(0, index))
                        }
                      }

                      result
                  }
                }
                case (_, Some(1)) => {
                  matrix map { submatrix => (submatrix.rowIndex, submatrix.rows, submatrix.rowOffset) } cross
                    matrix map {
                      case ((columnIndex, numColumns, columnOffset), submatrix) =>
                        val partition = Partition(-1, submatrix.rowIndex, columnIndex, submatrix.rows, numColumns,
                          submatrix.rowOffset, columnOffset, submatrix.totalRows, submatrix.totalRows)

                        val result = Submatrix(partition)

                        if (submatrix.rowIndex == columnIndex) {
                          for (index <- 0 until submatrix.rows) {
                            result.update(index, index, submatrix(index, 0))
                          }
                        }

                        result
                    }
                }
                case _ => {
                  val partialDiagResults = matrix map { submatrix =>
                    val partition = Partition(-1, submatrix.rowIndex, 0, submatrix.rows, 1, submatrix.rowOffset, 0,
                      submatrix.totalRows, 1)

                    val result = Submatrix(partition, submatrix.rows)

                    val rowStart = submatrix.rowOffset
                    val rowEnd = submatrix.rowOffset + submatrix.rows
                    val columnStart = submatrix.columnOffset
                    val columnEnd = submatrix.columnOffset + submatrix.cols

                    var indexStart = (-1, -1)
                    var indexEnd = (-1, -1)

                    if (rowStart <= columnStart && rowEnd > columnStart) {
                      indexStart = (columnStart - rowStart, 0)
                    }

                    if (columnStart < rowStart && columnEnd > rowStart) {
                      indexStart = (0, rowStart - columnStart)
                    }

                    if (rowStart < columnEnd && rowEnd >= columnEnd) {
                      indexEnd = (columnEnd - rowStart, submatrix.cols)
                    }

                    if (columnStart < rowEnd && columnEnd > rowEnd) {
                      indexEnd = (submatrix.rows, rowEnd - columnStart)
                    }

                    if (indexStart._1 != -1 && indexStart._2 != -1 && indexEnd._1 != -1 && indexEnd._2 != -1) {
                      for (counter <- 0 until indexEnd._1 - indexStart._1) {
                        result.update(counter + indexStart._1, 0, submatrix(indexStart._1 + counter,
                          indexStart._2 + counter))
                      }
                    } else {
                      assert(indexStart._1 == -1 && indexStart._2 == -1 && indexEnd._1 == -1 && indexEnd._2 == -1)
                    }

                    result
                  }

                  partialDiagResults groupBy { partialResult => partialResult.rowIndex } combinableReduceGroup {
                    results =>
                      val result = results.next.copy
                      results.foldLeft(result)(_ + _)
                  }
                }
              }
            }
          })
      } 

      case executable: FixpointIteration => {
        handle[FixpointIteration, (Matrix, Scalar[Double])](
          executable,
          { exec => (evaluate[Matrix](exec.initialState), evaluate[Scalar[Double]](exec.maxIterations)) },
          { case (exec, (initialState, maxIterations)) =>
            val numberIterations = maxIterations.contract.asInstanceOf[LiteralDataSource[Double]].values.head.toInt
            def stepFunction(partialSolution: Matrix) = {
              val oldStatePlaceholderValue = iterationStatePlaceholderValue
              iterationStatePlaceholderValue = Some(partialSolution)
              val result = evaluate[Matrix](exec.updatePlan)
              iterationStatePlaceholderValue = oldStatePlaceholderValue
              /* 
               * Iteration mechanism requires that there is some kind of operation in the step function.
               * Therefore it is not possible to use the identity function!!! A workaround for this situation
               * would be to apply an explicit mapping operation with the identity function.
              */
              result
            }

            val convergenceFlow = (prev: Matrix, cur: Matrix) => {
              val oldRegisteredValue0 = getRegisteredValue(0)
              val oldRegisteredValue1 = getRegisteredValue(1)
              registerValue(0, prev)
              registerValue(1, cur)
              val appliedConvergence = exec.convergence.apply(RegisteredValue(0), RegisteredValue(1))
              val result = evaluate[Scalar[Boolean]](appliedConvergence)
              oldRegisteredValue0 match {
                case Some(t) => registerValue(0,t)
                case None =>
              }
              oldRegisteredValue1 match{
                case Some(t) => registerValue(1, t)
                case None =>
              }

              result
            }

            val iteration = initialState.iterateWithConvergence(numberIterations, stepFunction, convergenceFlow)
            iteration.setName("Fixpoint iteration")
            iteration
          })
      }

      case IterationStatePlaceholder => {
        iterationStatePlaceholderValue match {
          case Some(value) => value
          case None => throw new StratosphereExecutionError("The iteration state placeholder value was not set yet.")
        }
      }

      case executable: sum => {
        handle[sum, (Matrix, Scalar[Double])](
          executable,
          { exec => (evaluate[Matrix](exec.matrix), evaluate[Scalar[Double]](exec.dimension)) },
          {
            case (_, (matrix, scalar)) =>
              scalar cross matrix map { (scalar, submatrix) =>
                if (scalar == 1) {
                  (submatrix.columnIndex, breeze.linalg.sum(submatrix(::, *)))
                } else {
                  (submatrix.rowIndex, breeze.linalg.sum(submatrix(*, ::)).asMatrix)
                }
              } groupBy { case (group, subvector) => group } combinableReduceGroup { submatrices =>
                val firstSubvector = submatrices.next
                (firstSubvector._1, submatrices.foldLeft(firstSubvector._2.copy)(_ + _._2))
              } map { case (_, submatrix) => submatrix}
          })
      }

      case executable: norm => {
        handle[norm, (Matrix, Scalar[Double])](
        executable,
        { exec => (evaluate[Matrix](exec.matrix), evaluate[Scalar[Double]](exec.p))},
        { case (_, (matrix, p)) =>
          val exponentiation = matrix cross p map { (matrix, p) => matrix :^ p }
          exponentiation.setName("Norm: Exponentiation")
          val sums = exponentiation map { (matrix) => matrix.activeValuesIterator.fold(0.0)( _ + _ )}
          sums.setName("Norm: Partial sums of submatrices")
          val result = sums.combinableReduceAll( it => it.fold(0.0)(_ + _))
          result.setName("Norm: Sum of partial sums")
          result
        }
        )
      }

      case function: function => {
        throw new StratosphereExecutionError("Cannot execute function. Needs function application")
      } 

      case parameter: StringParameter => {
        throw new StratosphereExecutionError("Parameter found. Cannot execute parameters.")
      }

      case parameter: ScalarParameter => {
        throw new StratosphereExecutionError("Parameter found. Cannot execute parameters.")
      }

      case parameter: MatrixParameter => {
        throw new StratosphereExecutionError("Parameter found. Cannot execute parameters.")
      }

      case parameter: FunctionParameter => {
        throw new StratosphereExecutionError("Parameter found. Cannot execute parameters.")
      }

      case registeredValue: RegisteredValue => {
        getRegisteredValue(registeredValue.index) match {
          case Some(t) => t
          case _ => throw new StratosphereExecutionError("Could not retrieve registered value.")
        }
      }

    }
  }
}