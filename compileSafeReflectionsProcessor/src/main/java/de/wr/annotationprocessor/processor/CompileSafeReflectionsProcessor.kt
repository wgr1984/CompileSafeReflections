package de.wr.annotationprocessor.processor

import com.github.javaparser.JavaParser.parseClassOrInterfaceType
import com.github.javaparser.JavaParser.parseType
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.*
import de.wr.libsimplecomposition.Reflect
import io.reactivex.Observable
import io.reactivex.rxkotlin.toObservable
import java.io.BufferedWriter
import java.io.IOException
import java.lang.*
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.SourceVersion.latestSupported
import javax.lang.model.element.*
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.MirroredTypesException
import javax.lang.model.type.TypeKind
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import kotlin.reflect.KClass
import com.github.javaparser.ast.Modifier as AstModifier
import com.github.javaparser.ast.type.Type as AstType

class CompileSafeReflectionsProcessor : AbstractProcessor() {

    private lateinit var objectType: String
    private lateinit var typeUtils: Types
    private lateinit var elementUtils: Elements
    private lateinit var filer: Filer
    private lateinit var messager: Messager

    override fun getSupportedSourceVersion(): SourceVersion = latestSupported()

    override fun getSupportedAnnotationTypes() = supportedAnnotations

    @Synchronized override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        typeUtils = processingEnv.typeUtils
        elementUtils = processingEnv.elementUtils
        filer = processingEnv.filer
        messager = processingEnv.messager
    }

    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {

        val clazzes = supportedAnnotationsClasses.toObservable()
            .flatMap {
                roundEnv.getElementsAnnotatedWith(it).toObservable()
            }.flatMap { element ->
                    try {
                        element.getAnnotation(Reflect::class.java).value
                    } catch (e: MirroredTypesException) {
                        return@flatMap e.typeMirrors.toObservable().cast(DeclaredType::class.java).map { it.asElement() }
                    }
                    Observable.just(element)
            }.toList().blockingGet()

        generateMethodsForClazz(clazzes)

        return true
    }

    private fun generateMethodsForClazz(clazzes: List<Element>) {

        clazzes.forEach {

            val clazzElement = it as TypeElement

            info(clazzElement, "Compile time reflection found %s", clazzElement.simpleName.toString() )

            try {
                createClass("${clazzElement.simpleName}Relections", clazzElement)
            } catch (e: IOException) {
                System.err.println(objectType + " :" + e + e.message)
                error(clazzElement, "Error: %s %n", e)
            }
        }
    }

    private fun createClass(fileName: String, clazzElement: TypeElement) {
        val source = processingEnv.filer.createSourceFile(fileName)

        val writer = BufferedWriter(source.openWriter())

        val cu = CompilationUnit()
        // set the package
        cu.setPackageDeclaration(getPackageName(clazzElement))

        val utilsClass = cu.addClass(fileName, AstModifier.PUBLIC)

        clazzElement.enclosedElements
                .filter { it.modifiers.contains(Modifier.PRIVATE) }
                .forEach { origMethod ->
                    // create method for each method / field
                    if (origMethod is ExecutableElement) {
                        createReflectionMethod(utilsClass, origMethod, clazzElement)
                    } else if (origMethod is VariableElement) {
                        createRelfectionFieldMethod(utilsClass, origMethod, clazzElement)
                    }

                }

        writer.run {
            write(cu.toString())
            flush()
            close()
        }
    }

    private fun createRelfectionFieldMethod(utilsClass: ClassOrInterfaceDeclaration, variable: VariableElement, clazzElement: TypeElement) {
        val variableString = variable.asType().toString()
        val methodName = variable.simpleName.toString()

        val getUtilsMethod = utilsClass.addMethod(methodName, AstModifier.PUBLIC, AstModifier.STATIC)
            .addParameter(clazzElement.asType().toString(), "obj")

        val setUtilsMethod = utilsClass.addMethod(methodName, AstModifier.PUBLIC, AstModifier.STATIC)
                .addParameter(clazzElement.asType().toString(), "obj")
                .addParameter(variableString, variable.simpleName.toString())


        val classType = parseClassOrInterfaceType(clazzElement.simpleName.toString())
        val methodType = parseClassOrInterfaceType(Field::class.java.canonicalName)
        val fieldRefField = "field"
        val returnType = parseType(variableString)

        val getTryBlock = BlockStmt().addStatement(VariableDeclarationExpr(
                        VariableDeclarator(methodType, fieldRefField,
                                MethodCallExpr(ClassExpr(classType), "getDeclaredField")
                                        .setArguments(NodeList<Expression>().apply {
                                            add(NameExpr("\"${variable.simpleName}\""))
                                        })
                        )
                ))
                .addStatement(MethodCallExpr(NameExpr(fieldRefField), "setAccessible")
                        .addArgument("true")
                )
        val setTryBlock = getTryBlock.clone()

        val getMethodInvoke: MethodCallExpr
        val setMethodInvoke: MethodCallExpr

        if (!variable.asType().kind.isPrimitive) {
            getMethodInvoke = MethodCallExpr(NameExpr(fieldRefField), "get")
                    .setArguments(NodeList<Expression>().apply {
                        add(NameExpr("obj"))
                    })
            setMethodInvoke = MethodCallExpr(NameExpr(fieldRefField), "set")
                    .setArguments(NodeList<Expression>().apply {
                        add(NameExpr("obj"))
                        add(NameExpr(variable.simpleName.toString()))
                    })
        } else {
            var returnTypeString = variableString
            returnTypeString =  "${returnTypeString.substring(0,1).toUpperCase()}${returnTypeString.substring(1, returnTypeString.length)}"
            getMethodInvoke = MethodCallExpr(NameExpr(fieldRefField), "get${returnTypeString}")
                .setArguments(NodeList<Expression>().apply {
                    add(NameExpr("obj"))
                })
            setMethodInvoke = MethodCallExpr(NameExpr(fieldRefField), "set${returnTypeString}")
                    .setArguments(NodeList<Expression>().apply {
                        add(NameExpr("obj"))
                        add(NameExpr(variable.simpleName.toString()))
                    })
        }

        getTryBlock.addStatement(ReturnStmt(CastExpr(returnType, getMethodInvoke)))
        setTryBlock.addStatement(setMethodInvoke)

        val getTryCatch = TryStmt()
                .setTryBlock(getTryBlock).setCatchClauses(NodeList<CatchClause>().apply {
            add(createConvertCatchClause(NoSuchFieldException::class))
            add(createConvertCatchClause(IllegalAccessException::class))
        })

        val setTryCatch = TryStmt()
                .setTryBlock(setTryBlock).setCatchClauses(NodeList<CatchClause>().apply {
            add(createConvertCatchClause(NoSuchFieldException::class))
            add(createConvertCatchClause(IllegalAccessException::class))
        })

        getUtilsMethod.setBody(
                BlockStmt()
                        .addStatement(getTryCatch)
        )
        getUtilsMethod.type = returnType

        setUtilsMethod.setBody(
                BlockStmt()
                        .addStatement(setTryCatch)
        )
        setUtilsMethod.setType("void")
    }

    private fun createReflectionMethod(utilsClass: ClassOrInterfaceDeclaration, origMethod: ExecutableElement, clazzElement: TypeElement) {
        val utilsMethod = utilsClass.addMethod(origMethod.simpleName.toString(), AstModifier.PUBLIC, AstModifier.STATIC)
        utilsMethod.addParameter(clazzElement.asType().toString(), "obj")
        origMethod.parameters.forEach {
            utilsMethod.addParameter(it.asType().toString(), it.simpleName.toString())
        }
        val classType = parseClassOrInterfaceType(clazzElement.simpleName.toString())
        val methodType = parseClassOrInterfaceType(Method::class.java.canonicalName)
        val methodRefField = "method"
        val returnType = parseType(origMethod.returnType.toString())

        val tryBlock = BlockStmt()
                .addStatement(VariableDeclarationExpr(
                        VariableDeclarator(methodType, methodRefField,
                                MethodCallExpr(ClassExpr(classType), "getDeclaredMethod")
                                        .setArguments(NodeList<Expression>().apply {
                                            add(NameExpr("\"${origMethod.simpleName}\""))
                                            origMethod.parameters.forEach {
                                                add(FieldAccessExpr(NameExpr(it.asType().toString()), "class"))
                                            }
                                        })
                        )
                ))
                .addStatement(MethodCallExpr(NameExpr(methodRefField), "setAccessible")
                        .addArgument("true")
                )

        val methodInvoke = MethodCallExpr(NameExpr(methodRefField), "invoke")
                .setArguments(NodeList<Expression>().apply {
                    add(NameExpr("obj"))
                    origMethod.parameters.forEach {
                        add(NameExpr(it.simpleName.toString()))
                    }
                })

        if (origMethod.returnType.kind != TypeKind.VOID) {
            tryBlock.addStatement(ReturnStmt(CastExpr(returnType, methodInvoke)))
        } else {
            tryBlock.addStatement(methodInvoke)
        }

        val tryCatch = TryStmt()
                .setTryBlock(tryBlock).setCatchClauses(NodeList<CatchClause>().apply {
            add(createConvertCatchClause(NoSuchMethodException::class))
            add(createConvertCatchClause(IllegalAccessException::class))
            add(createConvertCatchClause("java.lang.reflect.InvocationTargetException"))
        })
        utilsMethod.setBody(
                BlockStmt()
                        .addStatement(tryCatch)
        )
        utilsMethod.type = returnType
    }

    private fun createConvertCatchClause(clazz: KClass<out Exception>) = createConvertCatchClause(clazz.java.canonicalName)

    private fun createConvertCatchClause(clazz: String): CatchClause {
        return CatchClause(
                Parameter().setType(clazz).setName("e"),
                BlockStmt().addStatement(rethrow("e"))
        )
    }

    private fun rethrow(exception: String): ThrowStmt {
        return ThrowStmt(
                ObjectCreationExpr()
                        .setType(RuntimeException::class.java)
                        .addArgument(exception)
        )
    }

    private fun getPackageName(typeElement: TypeElement) =
            typeElement.qualifiedName.substring(0, typeElement.qualifiedName.length - typeElement.simpleName.length - 1)

    private fun error(e: Element, msg: String, vararg args: Any) {
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                String.format(msg, *args),
                e)
    }

    private fun info(e: Element, msg: String, vararg args: Any) {
        messager.printMessage(
                Diagnostic.Kind.WARNING,
                String.format(msg, *args),
                e)
    }


    companion object {
        private var supportedAnnotations = HashSet<String>()
        private var supportedAnnotationsClasses = mutableListOf<Class<out Annotation>>()

        init {
            supportedAnnotationsClasses.apply {
                add(Reflect::class.java)
            }.forEach { supportedAnnotations.add(it.canonicalName) }
        }
    }
}