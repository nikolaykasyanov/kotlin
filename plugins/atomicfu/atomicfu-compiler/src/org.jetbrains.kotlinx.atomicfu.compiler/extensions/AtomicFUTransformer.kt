/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.atomicfu.compiler.extensions

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.*
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder.buildValueParameter
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator.*

private const val AFU_PKG = "kotlinx.atomicfu"
private const val LOCKS = "locks"
private const val ATOMIC_VALUE_TYPE = """Atomic(Int|Long|Boolean|Ref)"""
private const val ATOMIC_ARRAY_TYPE = """Atomic(Int|Long|Boolean|)Array"""
private const val ATOMICFU_RUNTIME_FUNCTION_PREDICATE = "atomicfu_"
private const val REENTRANT_LOCK_TYPE = "ReentrantLock"
private const val GETTER = "atomicfu\$getter"
private const val SETTER = "atomicfu\$setter"
private const val GET = "get"
private const val ATOMIC_VALUE_FACTORY = "atomic"
private const val ATOMIC_ARRAY_OF_NULLS_FACTORY = "atomicArrayOfNulls"
private const val REENTRANT_LOCK_FACTORY = "reentrantLock"
private const val ATOMICFU_INLINE_FUNCTION = """atomicfu_(loop|update|getAndUpdate|updateAndGet)"""

class AtomicFUTransformer(private val context: IrPluginContext) {

    private val irBuiltIns = context.irBuiltIns

    private val AFU_CLASSES: Map<String, IrType> = mapOf(
        "AtomicInt" to irBuiltIns.intType,
        "AtomicLong" to irBuiltIns.longType,
        "AtomicRef" to irBuiltIns.anyNType,
        "AtomicBoolean" to irBuiltIns.booleanType
    )

    private val ATOMIC_VALUE_TYPE_REGEX = ATOMIC_VALUE_TYPE.toRegex()
    private val ATOMIC_ARRAY_TYPE_REGEX = ATOMIC_ARRAY_TYPE.toRegex()
    private val REENTRANT_LOCK_TYPE_REGEX = REENTRANT_LOCK_TYPE.toRegex()
    private val ATOMIC_FUNCTION_SIGNATURE_PATTERN = "$ATOMIC_VALUE_TYPE\\.(.*)".toRegex()

    fun transform(irFile: IrFile) {
        irFile.transform(AtomicExtensionTransformer(), null)
        irFile.transform(AtomicTransformer(), null)
    }

    inner class AtomicExtensionTransformer : IrElementTransformerVoid() {
        override fun visitFile(declaration: IrFile): IrFile {
            declaration.declarations.addAllTransformedAtomicExtensions()
            return super.visitFile(declaration)
        }

        override fun visitClass(declaration: IrClass): IrStatement {
            declaration.declarations.addAllTransformedAtomicExtensions()
            return super.visitClass(declaration)
        }

        private fun MutableList<IrDeclaration>.addAllTransformedAtomicExtensions() {
            val transformedDeclarations = mutableListOf<IrDeclaration>()
            forEach { irDeclaration ->
                irDeclaration.transformAtomicExtension()?.let { it -> transformedDeclarations.add(it) }
            }
            addAll(transformedDeclarations)
        }

        private fun IrDeclaration.transformAtomicExtension(): IrDeclaration? {
            // Transform the signature of the inline Atomic* extension declaration:
            // inline fun AtomicRef<T>.foo(arg) { ... } -> inline fun <T> foo(arg', atomicfu$getter: () -> T, atomicfu$setter: (T) -> Unit)
            if (this is IrFunction &&
                isInline &&
                extensionReceiverParameter != null &&
                extensionReceiverParameter!!.type.isAtomicValueType()
            ) {
                val newDeclaration = deepCopyWithSymbols(parent)
                val valueParametersCount = valueParameters.size
                val type = newDeclaration.extensionReceiverParameter!!.type.atomicToValueType()
                val getterType = context.buildGetterType(type)
                val setterType = context.buildSetterType(type)
                newDeclaration.valueParameters = newDeclaration.valueParameters + listOf(
                    buildValueParameter(newDeclaration, GETTER, valueParametersCount, getterType),
                    buildValueParameter(newDeclaration, SETTER, valueParametersCount + 1, setterType)
                )
                newDeclaration.extensionReceiverParameter = null
                return newDeclaration
            }
            return null
        }
    }

    inner class AtomicTransformer : IrElementTransformerVoid() {
        override fun visitFunction(declaration: IrFunction): IrStatement {
            // skip transformation of original atomic extension declarations
            if (declaration.isInline && declaration.extensionReceiverParameter != null && declaration.extensionReceiverParameter!!.type.isAtomicValueType())
                return declaration
            declaration.body?.transform(AtomicFunctionCallTransformer(declaration), null)
            return super.visitFunction(declaration)
        }

        override fun visitSetField(expression: IrSetField): IrExpression {
            if (expression.value is IrCall) {
                (expression.value as IrCall).eraseAtomicFactory()?.let { newExpression ->
                    expression.value = newExpression.transform(AtomicFunctionCallTransformer(expression.symbol.owner.parent), null)
                }
            }
            return super.visitSetField(expression)
        }

        override fun visitField(declaration: IrField): IrStatement {
            (declaration.initializer?.expression as? IrCall)?.let { expression ->
                expression.eraseAtomicFactory()?.let { newExpression ->
                    declaration.initializer = context.irFactory.createExpressionBody(
                        newExpression.transform(AtomicFunctionCallTransformer(declaration), null)
                    )
                }
            }
            return super.visitField(declaration)
        }

        override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
            // Erase constructor of Atomic(Int|Long|Boolean|)Array:
            // val arr = AtomicIntArray(size) -> val arr = new Int32Array(size)
            if (expression.isAtomicArrayConstructor()) {
                val arrayConstructorSymbol = context.getArrayConstructorSymbol(expression.type as IrSimpleType) { it.owner.valueParameters.size == 1 }
                val size = expression.getValueArgument(0)
                return IrConstructorCallImpl(
                    expression.startOffset, expression.endOffset,
                    arrayConstructorSymbol.owner.returnType, arrayConstructorSymbol,
                    arrayConstructorSymbol.owner.typeParameters.size, 0, 1
                ).apply {
                    putValueArgument(0, size)
                }
            }
            return super.visitConstructorCall(expression)
        }

        inner class AtomicFunctionCallTransformer(private val containingFunction: IrDeclarationParent) : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression {
                // For transformed atomic extension functions:
                // replace all usages of old value parameters with the new parameters of the transformed declaration
                // inline fun foo(arg', atomicfu$getter: () -> T, atomicfu$setter: (T) -> Unit) { bar(arg) } -> { bar(arg') }
                if (expression.symbol is IrValueParameterSymbol) {
                    val valueParameter = expression.symbol.owner as IrValueParameter
                    val parent = valueParameter.parent
                    if (parent is IrFunction && parent.isTransformedAtomicExtensionFunction()) {
                        val index = valueParameter.index
                        if (index >= 0) { // index == -1 for `this` parameter
                            val transformedValueParameter = parent.valueParameters[index]
                            return buildGetValue(
                                expression.startOffset,
                                expression.endOffset,
                                transformedValueParameter.symbol
                            )
                        }
                    }
                }
                return super.visitGetValue(expression)
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
                // Erase unchecked casts:
                // val a = atomic<Any>("AAA")
                // (a as AtomicRef<String>).value -> a.value
                if ((expression.operator == CAST || expression.operator == IMPLICIT_CAST) && expression.typeOperand.isAtomicValueType()) {
                    return expression.argument
                }
                return super.visitTypeOperator(expression)
            }

            override fun visitCall(expression: IrCall): IrExpression {
                val isInline = expression.symbol.owner.isInline
                (expression.extensionReceiver ?: expression.dispatchReceiver)?.transform(this, null)?.let { receiver ->
                    // Transform invocations of atomic functions
                    if (expression.symbol.isKotlinxAtomicfuPackage() && receiver.type.isAtomicValueType()) {
                        // Substitute invocations of atomic functions on atomic receivers
                        // with the corresponding inline declarations from `kotlinx-atomicfu-runtime`,
                        // passing atomic receiver accessors as atomicfu$getter and atomicfu$setter parameters.

                        // In case of the atomic field receiver, pass field accessors:
                        // a.incrementAndGet() -> atomicfu_incrementAndGet(get_a {..}, set_a {..})

                        // In case of the atomic `this` receiver, pass the corresponding atomicfu$getter and atomicfu$setter parameters
                        // from the parent transformed atomic extension declaration:
                        // Note: inline atomic extension signatures are already transformed with the [AtomicExtensionTransformer]
                        // inline fun foo(atomicfu$getter: () -> T, atomicfu$setter: (T) -> Unit) { incrementAndGet() } ->
                        // inline fun foo(atomicfu$getter: () -> T, atomicfu$setter: (T) -> Unit) { atomicfu_incrementAndGet(atomicfu$getter, atomicfu$setter) }
                        receiver.getReceiverAccessors(containingFunction)?.let { accessors ->
                            val receiverValueType = receiver.type.atomicToValueType()
                            val inlineAtomic = expression.inlineAtomicFunction(receiverValueType, accessors).apply {
                                if (symbol.owner.name.asString().matches(ATOMICFU_INLINE_FUNCTION.toRegex())) {
                                    val lambdaLoop = (getValueArgument(0) as IrFunctionExpression).function
                                    lambdaLoop.body?.transform(AtomicFunctionCallTransformer(lambdaLoop), null)
                                }
                            }
                            return super.visitCall(inlineAtomic)
                        }
                    }
                    // Transform invocations of atomic extension functions
                    if (isInline && receiver.type.isAtomicValueType()) {
                        // Transform invocation of the atomic extension on the atomic receiver,
                        // passing field accessors as atomicfu$getter and atomicfu$setter parameters.

                        // In case of the atomic field receiver, pass field accessors:
                        // a.foo(arg) -> foo(arg, get_a {..}, set_a {..})

                        // In case of the atomic `this` receiver, pass the corresponding atomicfu$getter and atomicfu$setter parameters
                        // from the parent transformed atomic extension declaration:
                        // Note: inline atomic extension signatures are already transformed with the [AtomicExtensionTransformer]
                        // inline fun bar(atomicfu$getter: () -> T, atomicfu$setter: (T) -> Unit) { ... }
                        // inline fun foo(atomicfu$getter: () -> T, atomicfu$setter: (T) -> Unit) { this.bar() } ->
                        // inline fun foo(atomicfu$getter: () -> T, atomicfu$setter: (T) -> Unit) { bar(atomicfu$getter, atomicfu$setter) }
                        receiver.getReceiverAccessors(containingFunction)?.let { accessors ->
                            val transformedAtomicExtension = expression.symbol.owner.getDeclarationWithAccessorParameters()
                            return buildCall(
                                expression.startOffset,
                                expression.endOffset,
                                target = transformedAtomicExtension.symbol,
                                type = expression.type,
                                origin = IrStatementOrigin.INVOKE,
                                valueArguments = expression.getValueArguments() + accessors
                            ).apply {
                                dispatchReceiver = expression.dispatchReceiver
                            }
                        }
                    }
                }
                return super.visitCall(expression)
            }
        }

        private fun IrExpression.getReceiverAccessors(parentDeclaration: IrDeclarationParent): List<IrExpression>? =
            when {
                this is IrCall -> getAccessors(parentDeclaration)
                isThisReceiver() -> {
                    if (parentDeclaration is IrFunction && parentDeclaration.isTransformedAtomicExtensionFunction()) {
                        parentDeclaration.valueParameters.takeLast(2).map { it.capture() }
                    } else null
                }
                else -> null
            }

        private fun IrExpression.isThisReceiver() =
            this is IrGetValue && symbol.owner.name.asString() == "<this>"

        private fun IrCall.inlineAtomicFunction(atomicType: IrType, accessors: List<IrExpression>): IrCall {
            val valueArguments = getValueArguments()
            val functionName = getAtomicFunctionName()
            val runtimeFunction = getRuntimeFunctionSymbol(functionName, atomicType)
            return buildCall(
                startOffset, endOffset,
                target = runtimeFunction,
                type = type,
                origin = IrStatementOrigin.INVOKE,
                typeArguments = if (runtimeFunction.owner.typeParameters.size == 1) listOf(atomicType) else emptyList(),
                valueArguments = valueArguments + accessors
            )
        }

        private fun IrFunction.hasReceiverAccessorParameters(): Boolean {
            if (valueParameters.size < 2) return false
            val params = valueParameters.takeLast(2)
            return params[0].name.asString() == GETTER && params[1].name.asString() == SETTER
        }

        private fun IrDeclaration.isTransformedAtomicExtensionFunction(): Boolean =
            this is IrFunction && this.hasReceiverAccessorParameters()

        private fun IrFunction.getDeclarationWithAccessorParameters(): IrSimpleFunction {
            val parent = parent as IrDeclarationContainer
            val paramsCount = valueParameters.size
            val receiverType = extensionReceiverParameter!!.type.atomicToValueType()
            return try {
                parent.declarations.single {
                    it is IrSimpleFunction &&
                            it.name == symbol.owner.name &&
                            it.valueParameters.size == paramsCount + 2 &&
                            it.valueParameters.dropLast(2).withIndex()
                                .all { p -> p.value.render() == valueParameters[p.index].render() } &&
                            it.valueParameters[paramsCount].name.asString() == GETTER && it.valueParameters[paramsCount + 1].name.asString() == SETTER &&
                            it.getGetterReturnType()!!.render() == receiverType.render()
                } as IrSimpleFunction
            } catch (e: RuntimeException) {
                error("Exception while looking for the declaration ${this.render()} with accessor parameters: ${e.message}")
            }
        }

        private fun IrCall.isArrayElementGetter() =
            dispatchReceiver != null &&
                    dispatchReceiver!!.type.isAtomicArrayType() &&
                    symbol.owner.name.asString() == GET

        private fun IrCall.getAccessors(parentDeclaration: IrDeclarationParent): List<IrExpression> {
            val isArrayElement = isArrayElementGetter()
            val valueType = type.atomicToValueType()
            return listOf(
                context.buildAccessorLambda(this, valueType, false, isArrayElement, parentDeclaration),
                context.buildAccessorLambda(this, valueType,true, isArrayElement, parentDeclaration)
            )
        }

        private fun getRuntimeFunctionSymbol(name: String, type: IrType): IrSimpleFunctionSymbol {
            val functionName = when (name) {
                "value.<get-value>" -> "getValue"
                "value.<set-value>" -> "setValue"
                else -> name
            }
            return context.referencePackageFunction("kotlinx.atomicfu", "$ATOMICFU_RUNTIME_FUNCTION_PREDICATE$functionName") {
                val typeArg = it.owner.getGetterReturnType()
                !(typeArg as IrType).isPrimitiveType() || typeArg == type
            }
        }

        private fun IrFunction.getGetterReturnType() =
            (valueParameters[valueParameters.lastIndex - 1].type as IrSimpleType).arguments.first().typeOrNull

        private fun IrCall.getAtomicFunctionName(): String {
            val signature = symbol.signature!!
            val classFqName = if (signature is IdSignature.AccessorSignature) {
                signature.accessorSignature.declarationFqName
            } else (signature.asPublic()!!).declarationFqName
            return ATOMIC_FUNCTION_SIGNATURE_PATTERN.findAll(classFqName).firstOrNull()?.let { it.groupValues[2] } ?: classFqName
        }

        private fun IrCall.eraseAtomicFactory() =
            when {
                isAtomicFactory() -> getValueArgument(0)!!
                isAtomicArrayFactory() -> buildPureTypeArrayConstructor()
                isReentrantLockFactory() -> context.buildConstNull()
                else -> null
            }

        private fun IrCall.buildPureTypeArrayConstructor(): IrCall {
            val arrayFactorySymbol = context.referencePackageFunction("kotlin", "arrayOfNulls")
            val arrayElementType = getTypeArgument(0)!!
            val size = getValueArgument(0)
            return buildCall(
                startOffset, endOffset,
                target = arrayFactorySymbol,
                type = type,
                typeArguments = listOf(arrayElementType),
                valueArguments = listOf(size)
            )
        }
    }

    private fun IrSymbol.isKotlinxAtomicfuPackage() =
        this.isPublicApi && signature?.packageFqName()?.asString() == AFU_PKG

    private fun IrType.isAtomicValueType() = belongsTo(ATOMIC_VALUE_TYPE_REGEX)
    private fun IrType.isAtomicArrayType() = belongsTo(ATOMIC_ARRAY_TYPE_REGEX)
    private fun IrType.isReentrantLockType() = belongsTo("$AFU_PKG.$LOCKS", REENTRANT_LOCK_TYPE_REGEX)

    private fun IrType.belongsTo(typeName: Regex) = belongsTo(AFU_PKG, typeName)

    private fun IrType.belongsTo(packageName: String, typeNameReg: Regex): Boolean {
        return classOrNull?.let {
            it.signature?.asPublic()?.let { sig ->
                sig.packageFqName == packageName && sig.declarationFqName.matches(typeNameReg)
            }
        } ?: false
    }

    private fun IrType.atomicToValueType(): IrType {
        val classId = ((this as IrSimpleType).classifier.signature!!.asPublic())!!.declarationFqName
        if (classId == "AtomicRef") {
            return arguments.first().typeOrNull ?: error("$AFU_PKG.AtomicRef type parameter is not IrTypeProjection")
        }
        return AFU_CLASSES[classId] ?: error("IrType ${this.getClass()} does not match any of atomicfu types")
    }

    private fun IrCall.isAtomicFactory(): Boolean =
        symbol.isKotlinxAtomicfuPackage() && symbol.owner.name.asString() == ATOMIC_VALUE_FACTORY &&
                type.isAtomicValueType()

    private fun IrCall.isAtomicArrayFactory(): Boolean =
        symbol.isKotlinxAtomicfuPackage() && symbol.owner.name.asString() == ATOMIC_ARRAY_OF_NULLS_FACTORY &&
                type.isAtomicArrayType()

    private fun IrConstructorCall.isAtomicArrayConstructor(): Boolean = type.isAtomicArrayType()

    private fun IrCall.isReentrantLockFactory(): Boolean =
        symbol.owner.name.asString() == REENTRANT_LOCK_FACTORY && type.isReentrantLockType()
}
