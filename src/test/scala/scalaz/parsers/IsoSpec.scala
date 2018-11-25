package scalaz.parsers

import org.specs2.mutable.Specification
import scalaz.tc._

class IsoSpec extends Specification {

  private type Id[A]      = A
  private type TFun[A, B] = A => Id[B]
  private type TIso[A, B] = Iso[Id, Id, A, B]

  implicit private val funCategory: Category[TFun] = instanceOf(
    new CategoryClass[TFun] {
      def id[A]: TFun[A, A]                                          = identity
      def compose[A, B, C](f: TFun[B, C], g: TFun[A, B]): TFun[A, C] = g.andThen(f)
    }
  )

  implicit private val idApplicative: Applicative[Id] = instanceOf(
    new ApplicativeClass[Id] {
      def pure[A](a: A): Id[A]                      = a
      def ap[A, B](fa: Id[A])(f: Id[A => B]): Id[B] = f(fa)
      def map[A, B](fa: Id[A])(f: A => B): Id[B]    = f(fa)
    }
  )

  private def unitL[A](a: A): TIso[Unit, A] = new TIso[Unit, A] {
    def to: UFV[Unit, A]   = _ => a
    def from: UGV[A, Unit] = _ => ()
  }

  private def unitR[A](a: A): TIso[A, Unit] = new TIso[A, Unit] {
    def to: UFV[A, Unit]   = _ => ()
    def from: UGV[Unit, A] = _ => a
  }

  private def verify[A, B](iso: TIso[A, B], a: A, b: B) =
    (iso.to(a) must_=== b)
      .and(iso.from(b) must_=== a)
      .and(iso.from(iso.to(a)) must_=== a)

  "Constructing Iso" >> {
    "via associate" in {
      verify(
        Iso.associate[Id, Id, Int, Long, String],
        (1, (2L, "s")),
        ((1, 2L), "s")
      )
    }
    "via flatten" in {
      verify(
        Iso.flatten[Id, Id, Int, Long, String],
        (1, (2L, "s")),
        (1, 2L, "s")
      )
    }
  }

  "Transforming Iso" >> {
    "via associate" in {
      val iso1: TIso[Unit, (Int, (Long, String))] = unitL((1, (2L, "s")))
      val iso2: TIso[Unit, ((Int, Long), String)] = iso1 >>> Iso.associate

      verify(iso2, (), ((1, 2L), "s"))

      val iso3: TIso[((Int, Long), String), Unit] = unitR(((1, 2L), "s"))
      val iso4: TIso[(Int, (Long, String)), Unit] = iso3 <<< Iso.associate

      verify(iso4, (1, (2L, "s")), ())
    }
    "via flatten" in {
      val iso1: TIso[Unit, (Int, (Long, String))] = unitL((1, (2L, "s")))
      val iso2: TIso[Unit, (Int, Long, String)]   = iso1 >>> Iso.flatten

      verify(iso2, (), (1, 2L, "s"))

      val iso3: TIso[(Int, Long, String), Unit]   = unitR((1, 2L, "s"))
      val iso4: TIso[(Int, (Long, String)), Unit] = iso3 <<< Iso.flatten

      verify(iso4, (1, (2L, "s")), ())
    }
  }
}
