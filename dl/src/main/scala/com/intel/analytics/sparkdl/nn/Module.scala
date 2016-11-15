/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.sparkdl.nn

import com.intel.analytics.sparkdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.sparkdl.tensor.{Storage, Tensor}
import com.intel.analytics.sparkdl.utils.{File, T, Table, Activities}
import org.apache.commons.lang3.SerializationUtils

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

abstract class TensorModule[@specialized(Float, Double) T: ClassTag]
  (implicit ev: TensorNumeric[T]) extends Module[Tensor[T], Tensor[T], T]

abstract class Module[A <: Activities: ClassTag, B <: Activities: ClassTag,
  @specialized(Float, Double) T: ClassTag](
  implicit ev: TensorNumeric[T]) extends Serializable {

  var output: B = Activities[B, T]().asInstanceOf[B]
  var gradInput: A = Activities[A, T]().asInstanceOf[A]

  def clearState() : this.type = {
    if(output.isInstanceOf[Tensor[T]]) {
      output.asInstanceOf[Tensor[T]].set()
    }

    if(gradInput.isInstanceOf[Tensor[T]]) {
      gradInput.asInstanceOf[Tensor[T]].set()
    }

    this
  }

  def setup() : this.type = {
    this
  }

  private var name : String = null

  def setName(name : String) : this.type = {
    this.name = name
    this
  }

  def getName() : String = {
    if (this.name == null) this.getClass.getName else this.name
  }

  protected var forwardTime = 0L

  protected var backwardTime = 0L

  def getTimes(): Array[(Module[_ <: Activities, _ <: Activities, T], Long, Long)] = {
    Array((this, forwardTime, backwardTime))
  }

  def resetTimes(): Unit = {
    forwardTime = 0
    backwardTime = 0
  }

  final def forward(input: A): B = {
    val before = System.nanoTime()
    val result = updateOutput(input)
    forwardTime += System.nanoTime() - before
    result
  }

  def backward(input: A, gradOutput: B): A = {
    val before = System.nanoTime()
    val result = updateGradInput(input, gradOutput)
    accGradParameters(input, gradOutput)
    backwardTime += System.nanoTime() - before
    result
  }

  def updateOutput(input: A): B = {
    this.output = input.asInstanceOf[B]
    output
  }

  def updateOutput(input: A, flag: Int): B = {
    this.output = input.asInstanceOf[B]
    output
  }

  def updateGradInput(input: A, gradOutput: B): A

  def accGradParameters(input: A, gradOutput: B, scale: Double = 1.0): Unit = {}

  def zeroGradParameters(): Unit = {}

  def updateParameters(learningRate: T): Unit = {}

  def getParameters(): (Tensor[T], Tensor[T]) = {
    val (weightParameters, gradParameters) = this.parameters()
    (Module.flatten[T](weightParameters), Module.flatten[T](gradParameters))
  }

  def parameters(): (Array[Tensor[T]], Array[Tensor[T]]) = null

  protected var train: Boolean = true

  def training(): this.type = {
    train = true
    this
  }

  def evaluate(): this.type = {
    train = false
    this
  }

  final def isTraining(): Boolean = {
    this.train
  }

  def reset(): Unit = {}

  protected var line = "\n"

  def setLine(line: String): this.type = {
    this.line = line
    this
  }

  def cloneModule(): Module[A, B, T] = {
    SerializationUtils.clone(this)
  }

  def canEqual(other: Any): Boolean = other.isInstanceOf[Module[A, B, T]]

  override def equals(other: Any): Boolean = other match {
    case that: Module[A, B, T] =>
      (that canEqual this) &&
        output == that.output &&
        gradInput == that.gradInput
    case _ => false
  }

  override def hashCode(): Int = {
    def getHashCode(a: Object): Int = if (a == null) 0 else a.hashCode()
    val state = Seq(output, gradInput, this.getClass)
    state.map(getHashCode).foldLeft(0)((a, b) => 31 * a + b)
  }

  def save(path : String, overWrite: Boolean = false) : this.type = {
    this.clearState()
    File.save(this, path, overWrite)
    this.setup()
    this
  }
}

object Module {
  def load[A <: Activities: ClassTag, B <: Activities: ClassTag,
  @specialized(Float, Double) T: ClassTag](path : String) : Module[A, B, T] = {
    File.load[Module[A, B, T]](path).setup()
  }

  def flatten[@specialized(Float, Double) T: ClassTag](parameters: Array[Tensor[T]])(
    implicit ev: TensorNumeric[T]): Tensor[T] = {
    val compactedTensor = isCompact(parameters)
    if (compactedTensor != null) {
      return compactedTensor
    }
    var i = 0
    var length = 0
    while (i < parameters.length) {
      require(parameters(i).isContiguous())
      length += parameters(i).nElement()
      i += 1
    }

    val result = Tensor[T](length)
    val resultStorage = result.storage()

    i = 0
    var offset = 0
    while (i < parameters.length) {
      System.arraycopy(parameters(i).storage().array(), parameters(i).storageOffset() - 1,
        resultStorage.array(), offset, parameters(i).nElement())
      parameters(i).set(resultStorage, offset + 1, parameters(i).size(), parameters(i).stride())
      offset += parameters(i).nElement()
      i += 1
    }

    result
  }

  def isCompact[@specialized(Float, Double) T: ClassTag](paramters: Array[Tensor[T]])(
    implicit ev: TensorNumeric[T]): Tensor[T] = {
    require(paramters.length > 0)
    var i = 1
    val storage = paramters(0).storage()
    var length = paramters(0).nElement()
    while (i < paramters.length) {
      if (!storage.eq(paramters(i).storage())) {
        return null
      }
      length += paramters(i).nElement()
      i += 1
    }

    if (length != storage.array().length) {
      return null
    }

    return Tensor(storage)
  }
}




