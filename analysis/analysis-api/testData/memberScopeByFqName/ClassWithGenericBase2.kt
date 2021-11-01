// DO_NOT_CHECK_SYMBOL_RESTORE
package test

class Foo

abstract class Base<T> {
    val noGeneric: Foo? = null

    val withOuterGeneric: T? = null

    val <TT> TT.withOwnGeneric: TT? get() = null

    val <TT> TT.withOuterAndOwnGeneric: T? get() = null
}

class ClassWithGenericBase : Base<Foo>

// class: test/ClassWithGenericBase