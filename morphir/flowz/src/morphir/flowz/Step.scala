package morphir.flowz

import zio._
import zio.clock.Clock

import scala.util.Try

final case class Step[-StateIn, +StateOut, -Env, -Params, +Err, +Value](
  private[flowz] val rawEffect: ZIO[StepContext[Env, StateIn, Params], Err, StepOutputs[StateOut, Value]],
  name: Option[String] = None,
  description: Option[String] = None
) { self =>

  /**
   * Connect this Step to the given Step. By connecting the output state of this Step to the input state of the other Step
   * and by connecting the output value of this Step to the input of the other.
   */
  def >>>[SOut2, Env1 <: Env, Err1 >: Err, Output2](
    that: Step[StateOut, SOut2, Env1, Value, Err1, Output2]
  ): Step[StateIn, SOut2, Env1, Params, Err1, Output2] =
    self andThen that

  def *>[StateIn1 <: StateIn, Env1 <: Env, Params1 <: Params, Err1 >: Err, StateOut2, Output2](
    that: Step[StateIn1, StateOut2, Env1, Params1, Err1, Output2]
  ): Step[StateIn1, StateOut2, Env1, Params1, Err1, Output2] =
    Step(self.effect *> that.effect)

  def <*>[StateIn1 <: StateIn, Env1 <: Env, In1 <: Params, Err1 >: Err, StateOut2, Output2](
    that: Step[StateIn1, StateOut2, Env1, In1, Err1, Output2]
  ): Step[StateIn1, (StateOut, StateOut2), Env1, In1, Err1, (Value, Output2)] = self zip that

  def |+|[StateIn1 <: StateIn, Env1 <: Env, In1 <: Params, Err1 >: Err, StateOut2, Output2](
    that: Step[StateIn1, StateOut2, Env1, In1, Err1, Output2]
  ): Step[StateIn1, (StateOut, StateOut2), Env1, In1, Err1, (Value, Output2)] =
    Step((self.effect zipPar that.effect).map { case (left, right) => left zip right })

  def <&>[StateIn1 <: StateIn, Env1 <: Env, In1 <: Params, Err1 >: Err, StateOut2, Output2](
    that: Step[StateIn1, StateOut2, Env1, In1, Err1, Output2]
  ): Step[StateIn1, (StateOut, StateOut2), Env1, In1, Err1, (Value, Output2)] =
    Step((self.effect zipPar that.effect).map { case (left, right) => left zip right })

  def <*[StateIn1 <: StateIn, Env1 <: Env, Params1 <: Params, Err1 >: Err, StateOut2, Output2](
    that: Step[StateIn1, StateOut2, Env1, Params1, Err1, Output2]
  ): Step[StateIn1, StateOut, Env1, Params1, Err1, Value] =
    Step(self.effect <* that.effect)

  /**
   * Adapts the input provided to the Step using the provided function.
   */
  def adaptParameters[Input0](func: Input0 => Params): Step[StateIn, StateOut, Env, Input0, Err, Value] =
    new Step[StateIn, StateOut, Env, Input0, Err, Value](self.effect.provideSome { ctx =>
      ctx.copy(inputs = ctx.inputs.copy(params = func(ctx.inputs.params)))
    })

  def andThen[SOut2, Env1 <: Env, Err1 >: Err, Output2](
    that: Step[StateOut, SOut2, Env1, Value, Err1, Output2]
  ): Step[StateIn, SOut2, Env1, Params, Err1, Output2] =
    Step(ZIO.environment[StepContext[Env1, StateIn, Params]].flatMap { ctx =>
      self.effect.flatMap(out => that.effect.provide(ctx.updateInputs(out)))
    })

  def andThenEffect[Err1 >: Err, StateOut2, Output2](
    thatEffect: ZIO[Value, Err1, StepOutputs[StateOut2, Output2]]
  ): Step[StateIn, StateOut2, Env, Params, Err1, Output2] =
    Step(self.effect.map(out => out.value) andThen thatEffect)

  /**
   * Maps the success value of this flow to the specified constant value.
   */
  def as[Out2](out: => Out2): Step[StateIn, StateOut, Env, Params, Err, Out2] = self.map(_ => out)

  def delay(duration: zio.duration.Duration): Step[StateIn, StateOut, Env with Clock, Params, Err, Value] =
    Step(
      for {
        ctx    <- ZIO.environment[StepContext[Env with Clock, StateIn, Params]]
        result <- self.effect.provide(ctx).delay(duration).provide(ctx.environment)
      } yield result
    )

  val effect: ZIO[StepContext[Env, StateIn, Params], Err, StepOutputs[StateOut, Value]] = rawEffect

  def flatMap[S, Env1 <: Env, P <: Params, Err1 >: Err, B](
    func: Value => Step[StateOut, S, Env1, P, Err1, B]
  ): Step[StateIn, S, Env1, P, Err1, B] =
    Step(ZIO.environment[StepContext[Env1, StateIn, P]].flatMap { ctx =>
      self.effect.flatMap(out => func(out.value).effect.provide(ctx.updateState(out.state)))
    })

  def flatten[S, Env1 <: Env, P <: Params, Err1 >: Err, B](implicit
    ev: Value <:< Step[StateOut, S, Env1, P, Err1, B]
  ): Step[StateIn, S, Env1, P, Err1, B] =
    flatMap(ev)

  def flipOutputs: Step[StateIn, Value, Env, Params, Err, StateOut] =
    self.mapOutputs { case (state, value) => (value, state) }

  def fork: ForkedStep[StateIn, StateOut, Env, Params, Err, Value] =
    Step[StateIn, Unit, Env, Params, Nothing, Fiber.Runtime[Err, StepOutputs[StateOut, Value]]](
      self.effect.fork.map { rt =>
        StepOutputs(rt)
      }
    )

  def map[Out2](fn: Value => Out2): Step[StateIn, StateOut, Env, Params, Err, Out2] = Step(
    self.effect.map(success => success.map(fn))
  )

  def mapEffect[Out2](fn: Value => Out2)(implicit
    ev: Err <:< Throwable
  ): Step[StateIn, StateOut, Env, Params, Throwable, Out2] =
    Step(self.effect.mapEffect(success => success.map(fn)))

  def mapError[Err2](onError: Err => Err2): Step[StateIn, StateOut, Env, Params, Err2, Value] =
    Step(self.effect.mapError(onError))

  def mapOutputs[StateOut2, Output2](
    func: (StateOut, Value) => (StateOut2, Output2)
  ): Step[StateIn, StateOut2, Env, Params, Err, Output2] =
    Step(self.effect.map(out => StepOutputs.fromTuple(func(out.state, out.value))))

  def mapOutputChannels[StateOut2, Output2](
    func: StepOutputs[StateOut, Value] => StepOutputs[StateOut2, Output2]
  ): Step[StateIn, StateOut2, Env, Params, Err, Output2] =
    Step(self.effect.map(func))

  def mapState[SOut2](fn: StateOut => SOut2): Step[StateIn, SOut2, Env, Params, Err, Value] = Step(
    self.effect.map(success => success.mapState(fn))
  )

  /**
   * Executes this step and returns its value, if it succeeds, but otherwise executes the specified step.
   */
  def orElse[StateIn1 <: StateIn, Env1 <: Env, Params1 <: Params, Err2, StateOut2 >: StateOut, Value2 >: Value](
    that: => Step[StateIn1, StateOut2, Env1, Params1, Err2, Value2]
  )(implicit ev: CanFail[Err]): Step[StateIn1, StateOut2, Env1, Params1, Err2, Value2] =
    Step(self.effect orElse that.effect)

  /**
   * Returns a step that will produce the value of this step, unless it
   * fails, in which case, it will produce the value of the specified step.
   */
  def orElseEither[StateIn1 <: StateIn, Env1 <: Env, Params1 <: Params, Err2, ThatState >: StateOut, ThatValue](
    that: => Step[StateIn1, ThatState, Env1, Params1, Err2, ThatValue]
  )(implicit
    ev: CanFail[Err]
  ): Step[StateIn1, Either[StateOut, ThatState], Env1, Params1, Err2, Either[Value, ThatValue]] =
    new Step((self.effect orElseEither that.effect).map {
      case Left(outputs)  => StepOutputs(state = Left(outputs.state), value = Left(outputs.value))
      case Right(outputs) => StepOutputs(state = Right(outputs.state), value = Right(outputs.value))
    })

  /**
   * Executes this step and returns its value, if it succeeds, but otherwise fails with the specified error.
   */
  def orElseFail[State >: StateOut, Err1](error: Err1)(implicit
    ev: CanFail[Err]
  ): Step[StateIn, StateOut, Env, Params, Err1, Value] =
    orElse(Step.fail(error))

  /**
   * Executes this step and returns its value, if it succeeds, but otherwise succeeds with the specified state and value.
   */
  def orElseSucceed[State >: StateOut, Value1 >: Value](state: => State, value: => Value1)(implicit
    ev: CanFail[Err]
  ): Step[StateIn, State, Env, Params, Nothing, Value1] =
    orElse(Step.succeed(state = state, value = value))

  def named(name: String): Step[StateIn, StateOut, Env, Params, Err, Value] = copy(name = Option(name))
  def describe(description: String): Step[StateIn, StateOut, Env, Params, Err, Value] =
    copy(description = Option(description))

  /**
   * Repeats the step the specified number of times.
   */
  def repeatN(n: Int): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(effect.repeatN(n))

  def repeatUntil(f: StepOutputs[StateOut, Value] => Boolean): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(effect.repeatUntil(f))

  def repeatUntil(statePredicate: StateOut => Boolean)(
    valuePredicate: Value => Boolean
  ): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(effect.repeatUntil(outputs => statePredicate(outputs.state) && valuePredicate(outputs.value)))

  def repeatWhile(f: StepOutputs[StateOut, Value] => Boolean): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(effect.repeatWhile(f))

  def repeatWhileState(f: StateOut => Boolean): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(effect.repeatWhile(outputs => f(outputs.state)))

  def repeatWhileValue(f: Value => Boolean): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(effect.repeatWhile(outputs => f(outputs.value)))

  def retryN(n: Int)(implicit ev: CanFail[Err]): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(effect.retryN(n))

  def retryWhile(f: StepOutputs[StateOut, Value] => Boolean): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(effect.repeatWhile(f))

  def run(implicit
    evAnyInput: Any <:< Params,
    evAnyState: Any <:< StateIn
  ): ZIO[Env, Err, StepOutputs[StateOut, Value]] =
    self.effect.provideSome[Env](env => StepContext(environment = env, params = (), state = ()))

  def run(input: Params)(implicit evAnyState: Unit <:< StateIn): ZIO[Env, Err, StepOutputs[StateOut, Value]] =
    self.effect.provideSome[Env](env => StepContext(environment = env, params = input, state = ()))

  def run(input: Params, initialState: StateIn): ZIO[Env, Err, StepOutputs[StateOut, Value]] =
    self.effect.provideSome[Env](env => StepContext(environment = env, params = input, state = initialState))

  def run(context: StepContext[Env, StateIn, Params]): IO[Err, StepOutputs[StateOut, Value]] =
    self.effect.provide(context)

  def shiftStateToOutput: Step[StateIn, Unit, Env, Params, Err, (StateOut, Value)] =
    Step(effect.map(success => StepOutputs(state = (), value = (success.state, success.value))))

  /**
   * Maps the output state value of this step to the specified constant value.
   */
  def stateAs[StateOut2](stateOut: => StateOut2): Step[StateIn, StateOut2, Env, Params, Err, Value] =
    self.mapState(_ => stateOut)

  /**
   * Takes the output state and makes it also available as the result value of this flow.
   */
  def stateAsValue: Step[StateIn, StateOut, Env, Params, Err, StateOut] =
    self.mapOutputs((state, _) => (state, state))

  def tap[Env1 <: Env, Err1 >: Err](
    func: (StateOut, Value) => ZIO[Env1, Err1, Any]
  ): Step[StateIn, StateOut, Env1, Params, Err1, Value] =
    Step(
      ZIO
        .environment[StepContext[Env1, StateIn, Params]]
        .flatMap(ctx => self.effect.tap(out => func(out.state, out.value).provide(ctx.environment)))
    )

  def tapState[Env1 <: Env, Err1 >: Err](
    func: StateOut => ZIO[Env1, Err1, Any]
  ): Step[StateIn, StateOut, Env1, Params, Err1, Value] =
    Step(
      ZIO
        .environment[StepContext[Env1, StateIn, Params]]
        .flatMap(ctx => self.effect.tap(out => func(out.state).provide(ctx.environment)))
    )

  def tapValue[Env1 <: Env, Err1 >: Err](
    func: Value => ZIO[Env1, Err1, Any]
  ): Step[StateIn, StateOut, Env1, Params, Err1, Value] =
    Step(
      ZIO
        .environment[StepContext[Env1, StateIn, Params]]
        .flatMap(ctx => self.effect.tap(out => func(out.value).provide(ctx.environment)))
    )

  def transformEff[StateOut2, Output2](
    func: (StateOut, Value) => (StateOut2, Output2)
  )(implicit ev: Err <:< Throwable): Step[StateIn, StateOut2, Env, Params, Throwable, Output2] =
    Step(self.effect.mapEffect(out => StepOutputs.fromTuple(func(out.state, out.value))))

  /**
   * Make the state and the output value the same by making the state equal to the output.
   */
  def valueAsState: Step[StateIn, Value, Env, Params, Err, Value] =
    self.mapOutputs { case (_, value) =>
      (value, value)
    }

  def zip[StateIn1 <: StateIn, Env1 <: Env, In1 <: Params, Err1 >: Err, StateOut2, Output2](
    that: Step[StateIn1, StateOut2, Env1, In1, Err1, Output2]
  ): Step[StateIn1, (StateOut, StateOut2), Env1, In1, Err1, (Value, Output2)] =
    Step((self.effect zip that.effect).map { case (left, right) => left zip right })

  def zipPar[StateIn1 <: StateIn, Env1 <: Env, In1 <: Params, Err1 >: Err, StateOut2, Output2](
    that: Step[StateIn1, StateOut2, Env1, In1, Err1, Output2]
  ): Step[StateIn1, (StateOut, StateOut2), Env1, In1, Err1, (Value, Output2)] =
    Step((self.effect zipPar that.effect).map { case (left, right) => left zip right })

  def zipWith[
    StateIn1 <: StateIn,
    Env1 <: Env,
    In1 <: Params,
    Err1 >: Err,
    StateOut2,
    Output2,
    FinalState,
    FinalOutput
  ](that: Step[StateIn1, StateOut2, Env1, In1, Err1, Output2])(
    f: (
      StepOutputs[StateOut, Value],
      StepOutputs[StateOut2, Output2]
    ) => StepOutputs[FinalState, FinalOutput]
  ): Step[StateIn1, FinalState, Env1, In1, Err1, FinalOutput] =
    Step((self.effect zipWith that.effect)(f))

  def zipWithPar[
    StateIn1 <: StateIn,
    Env1 <: Env,
    In1 <: Params,
    Err1 >: Err,
    StateOut2,
    Output2,
    FinalState,
    FinalOutput
  ](that: Step[StateIn1, StateOut2, Env1, In1, Err1, Output2])(
    f: (StepOutputs[StateOut, Value], StepOutputs[StateOut2, Output2]) => StepOutputs[FinalState, FinalOutput]
  ): Step[StateIn1, FinalState, Env1, In1, Err1, FinalOutput] =
    Step((self.effect zipWithPar that.effect)(f))
}

object Step extends StepCompanion[Any] {

  def apply[StateIn, StateOut, Env, Params, Err, Value](
    func: (StateIn, Params) => ZIO[Env, Err, StepOutputs[StateOut, Value]]
  ): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(
      ZIO
        .environment[StepContext[Env, StateIn, Params]]
        .flatMap(ctx => func(ctx.inputs.state, ctx.inputs.params).provide(ctx.environment))
    )

  def apply[StateIn, StateOut, Env, Params, Err, Value](name: String)(
    func: (StateIn, Params) => ZIO[Env, Err, StepOutputs[StateOut, Value]]
  ): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(
      ZIO
        .environment[StepContext[Env, StateIn, Params]]
        .flatMap(ctx => func(ctx.inputs.state, ctx.inputs.params).provide(ctx.environment)),
      name = Option(name)
    )

  def apply[StateIn, StateOut, Env, Params, Err, Value](name: String, description: String)(
    func: (StateIn, Params) => ZIO[Env, Err, StepOutputs[StateOut, Value]]
  ): Step[StateIn, StateOut, Env, Params, Err, Value] =
    Step(
      ZIO
        .environment[StepContext[Env, StateIn, Params]]
        .flatMap(ctx => func(ctx.inputs.state, ctx.inputs.params).provide(ctx.environment)),
      name = Option(name),
      description = Option(description)
    )

  def fromEither[Err, Value](value: Either[Err, Value]): Step[Any, Unit, Any, Any, Err, Value] =
    Step(for {
      _     <- ZIO.environment[StepContext.having.AnyInputs]
      value <- ZIO.fromEither(value)
    } yield StepOutputs.fromValue(value))

  def fromFunction[In, Out](func: In => Out): Step[Any, Out, Any, In, Nothing, Out] =
    Step(ZIO.environment[StepContext.having.Parameters[In]].map { ctx =>
      val value = func(ctx.inputs.params)
      StepOutputs(value = value, state = value)
    })

  def fromOption[Value](value: => Option[Value]): Step[Any, Unit, Any, Any, Option[Nothing], Value] =
    Step(for {
      _     <- ZIO.environment[StepContext.having.AnyInputs]
      value <- ZIO.fromOption(value)
    } yield StepOutputs.fromValue(value))

  def fromOutputs[State, Output](channels: StepOutputs[State, Output]): Step[Any, State, Any, Any, Nothing, Output] =
    Step(ZIO.succeed(channels))

  def fromTry[Value](value: => Try[Value]): Step[Any, Unit, Any, Any, Throwable, Value] =
    Step(for {
      _     <- ZIO.environment[StepContext.having.AnyInputs]
      value <- ZIO.fromTry(value)
    } yield StepOutputs.fromValue(value))

  def inputs[StateIn, Params]: Step[StateIn, (StateIn, Params), Any, Params, Nothing, (StateIn, Params)] =
    Step(
      ZIO
        .environment[StepContext[Any, StateIn, Params]]
        .map(ctx =>
          StepOutputs(state = (ctx.inputs.state, ctx.inputs.params), value = (ctx.inputs.state, ctx.inputs.params))
        )
    )

  def join[State, Err, Output](fiber: Fiber[Err, StepOutputs[State, Output]]): Step[Any, State, Any, Any, Err, Output] =
    Step(fiber.join)

  def stage[StateIn, StateOut, Env, Params, Err, Out](
    func: (StateIn, Params) => Step[StateIn, StateOut, Env, Params, Err, Out]
  ): Step[StateIn, StateOut, Env, Params, Err, Out] =
    Step.context[Env, StateIn, Params].flatMap(ctx => func(ctx.inputs.state, ctx.inputs.params))

  def state[State]: Step[State, State, Any, Any, Nothing, State] = Step(
    ZIO.environment[StepContext[Any, State, Any]].map(ctx => StepOutputs.setBoth(ctx.inputs.state))
  )

  def stateful[StateIn, Params, StateOut, Out](
    func: (StateIn, Params) => (StateOut, Out)
  ): Step[StateIn, StateOut, Any, Params, Nothing, Out] =
    Step(ZIO.environment[StepContext.having.AnyEnv[StateIn, Params]].map { ctx =>
      val (state, value) = func(ctx.inputs.state, ctx.inputs.params)
      StepOutputs(state = state, value = value)
    })

  def statefulEffect[StateIn, Params, StateOut, Out](
    func: (StateIn, Params) => (StateOut, Out)
  ): Step[StateIn, StateOut, Any, Params, Throwable, Out] =
    Step(ZIO.environment[StepContext.having.AnyEnv[StateIn, Params]].mapEffect { ctx =>
      val (state, value) = func(ctx.inputs.state, ctx.inputs.params)
      StepOutputs(state = state, value = value)
    })

  /**
   * Create a `Step` by providing a function that takes in some state and parameters and returns a tuple of
   * the output state and the result.
   */
  def step[StateIn, StateOut, Params, Out](
    func: (StateIn, Params) => (StateOut, Out)
  ): Step[StateIn, StateOut, Any, Params, Throwable, Out] =
    Step(ZIO.environment[StepContext[Any, StateIn, Params]].mapEffect { ctx =>
      val (state, value) = func(ctx.inputs.state, ctx.inputs.params)
      StepOutputs(state = state, value = value)
    })

  /**
   * Returns a step with the empty value.
   */
  val none: Step[Any, Option[Nothing], Any, Any, Nothing, Option[Nothing]] =
    Step(ZIO.environment[StepContext.having.AnyInputs].as(StepOutputs.none))

  /**
   * A step that succeeds with a unit value.
   */
  val unit: Step[Any, Unit, Any, Any, Nothing, Unit] =
    Step(ZIO.environment[StepContext.having.AnyInputs].as(StepOutputs.unit))

  def withStateAs[State](state: => State): Step[Any, State, Any, Any, Nothing, Unit] =
    Step(ZIO.succeed(StepOutputs.fromState(state)))

  def withValue[Value](value: => Value): Step[Any, Unit, Any, Any, Nothing, Value] =
    Step(ZIO.succeed(StepOutputs.fromValue(value)))

  def withStateAndValue[A](valueAndSate: => A): Step[Any, A, Any, Any, Nothing, A] =
    Step(ZIO.succeed(valueAndSate).map(StepOutputs.setBoth(_)))

  def withEnvironment[Env, Err, State, Value](
    func: Env => ZIO[Env, Err, (State, Value)]
  ): Step[Any, State, Env, Any, Err, Value] =
    Step(
      ZIO
        .environment[StepContext[Env, Any, Any]]
        .flatMap(ctx =>
          func(ctx.environment).map { case (state, value) =>
            StepOutputs(state = state, value = value)
          }.provide(ctx.environment)
        )
    )

  def withEnvironment[Env, Err, State, Value](
    effect: ZIO[Env, Err, (State, Value)]
  ): Step[Any, State, Env, Any, Err, Value] =
    Step(
      ZIO
        .environment[StepContext[Env, Any, Any]]
        .flatMap(ctx =>
          effect.map { case (state, value) =>
            StepOutputs(state = state, value = value)
          }.provide(ctx.environment)
        )
    )

  def withOutputs[A](valueAndSate: A): Step[Any, A, Any, Any, Nothing, A] =
    succeed(state = valueAndSate, value = valueAndSate)

  def withParams[Env, Params, Err, Out](func: Params => ZIO[Env, Err, Out]): Step[Any, Unit, Env, Params, Err, Out] =
    Step.parameters[Params].flatMap { params =>
      Step.fromEffect(func(params))
    }
}
