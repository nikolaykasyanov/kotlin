/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dfa

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

abstract class VariableStorage {
    abstract fun getRealVariableWithoutUnwrappingAlias(symbol: FirBasedSymbol<*>?, fir: FirElement, flow: Flow): RealVariable?
    abstract fun getRealVariable(symbol: FirBasedSymbol<*>?, fir: FirElement, flow: Flow): RealVariable?
    abstract fun getSyntheticVariable(fir: FirElement): SyntheticVariable?
    abstract fun getVariable(fir: FirElement, flow: Flow): DataFlowVariable?
}
