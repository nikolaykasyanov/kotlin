// DO_NOT_CHECK_SYMBOL_RESTORE
package test

class Foo

abstract class Base<T> {
    fun noGeneric() {}
    
    fun withOuterGeneric(t: T) {}
    
    fun <TT> withOwnGeneric(tt: TT) {}
    
    fun <TT> withOuterAndOwnGeneric(t: T, tt: TT) {}
}

class ClassWithGenericBase : Base<Foo>

// class: test/ClassWithGenericBase