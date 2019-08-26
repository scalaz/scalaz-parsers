package org.spartanz.parserz

import scala.annotation.tailrec

trait ParsersModule {
  type Input

  sealed abstract class Grammar[-SI, +SO, +E, A] {
    self =>

    import Grammar._

    final def map[B](to: A => B, from: B => A): Grammar[SI, SO, E, B] =
      Grammar.Map[SI, SO, E, A, B](self, a => Right(to(a)), b => Right(from(b)))

    final def mapPartial[E1 >: E, B](e: E1)(to: A =?> B, from: B =?> A): Grammar[SI, SO, E1, B] =
      Grammar.Map[SI, SO, E1, A, B](self, asEither(e)(to.lift), asEither(e)(from.lift))

    final def filter[E1 >: E](e: E1)(f: A => Boolean): Grammar[SI, SO, E1, A] =
      mapPartial[E1, A](e)({ case a if f(a) => a }, { case a if f(a) => a })

    final def zip[SI1 <: SI, SO1 >: SO, E1 >: E, B](that: Grammar[SI1, SO1, E1, B]): Grammar[SI1, SO1, E1, A /\ B] =
      Zip(self, that)

    final def alt[SI1 <: SI, SO1 >: SO, E1 >: E, B](that: Grammar[SI1, SO1, E1, B]): Grammar[SI1, SO1, E1, A \/ B] =
      Alt(self, that)

    final def ∘ [B](to: A => B, from: B => A): Grammar[SI, SO, E, B] = map(to, from)

    final def ~ [SI1 <: SI, SO1 >: SO, E1 >: E, B](that: Grammar[SI1, SO1, E1, B]): Grammar[SI1, SO1, E1, A /\ B] = self zip that

    final def | [SI1 <: SI, SO1 >: SO, E1 >: E, B](that: Grammar[SI1, SO1, E1, B]): Grammar[SI1, SO1, E1, A \/ B] = self alt that

    final def rep: Grammar[SI, SO, E, List[A]] = Rep(self)

    final def rep1: Grammar[SI, SO, E, ::[A]] = Rep1(self)

    final def @@(tag: String): Grammar[SI, SO, E, A] = Tag(self, tag)

    final def tag(tag: String): Grammar[SI, SO, E, A] = self @@ tag
  }

  object Grammar {
    private def asEither[E, A, B](e: E)(f: A => Option[B]): A => E \/ B =
      f(_).map(Right(_)).getOrElse(Left(e))

    private[parserz] case class Unit0() extends Grammar[Any, Nothing, Nothing, Unit]
    private[parserz] case class Consume0[SI, SO, E, A](to: Input => E \/ (Input, A), from: ((Input, A)) => E \/ Input) extends Grammar[Any, Nothing, E, A]
    private[parserz] case class Consume[SI, SO, E, A](to: (SI, Input) => (SO, E \/ (Input, A)), from: (SI, (Input, A)) => (SO, E \/ Input)) extends Grammar[SI, SO, E, A]
    private[parserz] case class Tag[SI, SO, E, A](value: Grammar[SI, SO, E, A], tag: String) extends Grammar[SI, SO, E, A]
    private[parserz] case class Map[SI, SO, E, A, B](value: Grammar[SI, SO, E, A], to: A => E \/ B, from: B => E \/ A) extends Grammar[SI, SO, E, B]
    private[parserz] case class Zip[SI, SO, E, A, B](left: Grammar[SI, SO, E, A], right: Grammar[SI, SO, E, B]) extends Grammar[SI, SO, E, A /\ B]
    private[parserz] case class Alt[SI, SO, E, A, B](left: Grammar[SI, SO, E, A], right: Grammar[SI, SO, E, B]) extends Grammar[SI, SO, E, A \/ B]
    private[parserz] case class Rep[SI, SO, E, A](value: Grammar[SI, SO, E, A]) extends Grammar[SI, SO, E, List[A]]
    private[parserz] case class Rep1[SI, SO, E, A](value: Grammar[SI, SO, E, A]) extends Grammar[SI, SO, E, ::[A]]

    final val unit: Grammar[Any, Nothing, Nothing, Unit] =
      Unit0()

    final def succeed[A](a: A): Grammar[Any, Nothing, Nothing, A] =
      unit.map[A](_ => a, _ => ())

    final def fail[E, A](e: E): Grammar[Any, Nothing, E, A] =
      unit.mapPartial(e)(PartialFunction.empty, PartialFunction.empty)

    final def consume[SI, SO, E, A](to: (SI, Input) => (SO, E \/ (Input, A)), from: (SI, (Input, A)) => (SO, E \/ Input)): Grammar[SI, SO, E, A] =
      Consume(to, from)

    final def consume0[A, E](to: Input => E \/ (Input, A), from: ((Input, A)) => E \/ Input): Grammar[Any, Nothing, E, A] =
      Consume0(to, from)

    final def consumeOptional0[E, A](e: E)(to: Input => Option[(Input, A)], from: ((Input, A)) => Option[Input]): Grammar[Any, Nothing, E, A] =
      consume0(asEither(e)(to), asEither(e)(from))
  }

  final def parser[S, E, A](grammar: Grammar[S, S, E, A]): (S, Input) => (S, E \/ (Input, A)) = {
    grammar match {
      case Grammar.Unit0()              => (s: S, i: Input) => (s, Right((i, ())))
      case Grammar.Consume0(to, _)      => (s: S, i: Input) => (s, to(i))
      case Grammar.Consume(to, _)       => (s: S, i: Input) => to(s, i)
      case Grammar.Tag(value, _)        => (s: S, i: Input) => parser(value)(s, i)

      case Grammar.Map(value, to, _)    =>
        (s: S, i: Input) => {
          val (s1, res1) = parser(value)(s, i)
          (s1, res1.flatMap { case (i1, a) => to(a).map(i1 -> _) })
        }

      case Grammar.Zip(left, right)     =>
        (s: S, i: Input) => {
          val ll = left.asInstanceOf[Grammar[Any, Any, E, Any]]
          val rr = right.asInstanceOf[Grammar[Any, Any, E, Any]]
          val res = parser(ll)(s, i) match {
            case (s1, Left(e))        => (s1, Left(e))
            case (s1, Right((i1, a))) =>
              val (s2, res2) = parser(rr)(s1, i1)
              (s2, res2.map { case (i2, b) => (i2, (a, b)) })
            }
          res.asInstanceOf[(Nothing, E \/ (Input, (Any, Any)))]
        }

      case Grammar.Alt(left, right)     => println(left.toString + right.toString); ???

      case Grammar.Rep(value)           =>
        (s: S, i: Input) => {
          val vv = value.asInstanceOf[Grammar[Any, Any, E, Any]]
          @tailrec
          def f(s: S, i: Input, as: List[Any]): (S, Input, List[Any]) =
            parser(vv)(s, i) match {
              case (_, Left(_))         => (s, i, as)
              case (s1, Right((i1, a))) => f(s1.asInstanceOf[S], i1, a :: as)
            }
          val (s1, i1, as) = f(s, i, Nil)
          (s1, Right((i1, as.reverse))).asInstanceOf[(Nothing, E \/ (Input, List[Any]))]
        }

      case Grammar.Rep1(value)          => println(value); ???
    }
  }

  final def printer[S, E, A](grammar: Grammar[S, S, E, A]): (S, (Input, A)) => (S, E \/ Input) =
    grammar match {
      case Grammar.Unit0()              => (s: S, a: (Input, A)) => (s, Right(a._1))
      case Grammar.Consume0(_, from)    => (s: S, a: (Input, A)) => (s, from(a))
      case Grammar.Consume(_, from)     => (s: S, a: (Input, A)) => from(s, a)
      case Grammar.Tag(value, _)        => (s: S, a: (Input, A)) => printer(value)(s, a)

      case Grammar.Map(value, _, from) =>
        (s: S, a: (Input, A)) =>
          from(a._2).fold(e => s -> Left(e), b => printer(value)(s, (a._1, b)))

      case Grammar.Zip(left, right)     =>
        // todo: use existential A and B here?
        (s: S, a: (Input, (Any, Any))) => {
          val ll = left.asInstanceOf[Grammar[Any, Any, E, Any]]
          val rr = right.asInstanceOf[Grammar[Any, Any, E, Any]]
          val (s1, res1) = printer(ll)(s, (a._1, a._2._1)).asInstanceOf[(S, E \/ Input)]
          val res2: (S, E \/ Input) = res1 match {
            case Left(e) => (s1, Left(e))
            case Right(i1) => printer(rr)(s1, (i1, a._2._2)).asInstanceOf[(S, E \/ Input)]
          }
          res2.asInstanceOf[(Nothing, E \/ Input)]
        }

      case Grammar.Alt(left, right)     => println(left + right.toString); ???

      case Grammar.Rep(value)           =>
        (s: S, a: (Input, List[Any])) => {
          val vv = value.asInstanceOf[Grammar[Any, Any, E, Any]]
          val res = a._2.foldLeft[(Any, E \/ Input)]((s, Right(a._1))) {
            case ((s0, Left(e)), _) => s0 -> Left(e)
            case ((s0, Right(i0)), a0) => printer(vv)(s0, (i0, a0))
          }
          res.asInstanceOf[(Nothing, E \/ Input)]
        }

      case Grammar.Rep1(value)          => println(value); ???
    }

  final def bnf[SI, SO, E, A](grammar: Grammar[SI, SO, E, A]): String = ???
}

object ParsersModule {

}