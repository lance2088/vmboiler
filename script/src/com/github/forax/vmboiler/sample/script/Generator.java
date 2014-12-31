package com.github.forax.vmboiler.sample.script;

import static org.objectweb.asm.Opcodes.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.Collectors;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.util.CheckClassAdapter;

import sun.misc.Unsafe;

import com.github.forax.vmboiler.CodeGen;
import com.github.forax.vmboiler.Constant;
import com.github.forax.vmboiler.Value;
import com.github.forax.vmboiler.sample.script.Expr.*;

@SuppressWarnings("restriction")
public class Generator {
  static class Env {
    final CodeGen codeGen;
    final Linker linker;
    final String linkerPlaceholder;
    final String nameAndType;
    final ConstantPoolPatch constantPoolPatch;
    final HashMap<Binding, Var> varMap;
    final HashMap<Expr, Binding> bindingMap;
    
    Env(CodeGen codeGen, Linker linker, String nameAndType, ConstantPoolPatch constantPoolPatch, HashMap<Expr, Binding> bindingMap) {
      this.codeGen = codeGen;
      this.linker = linker;
      this.linkerPlaceholder = constantPoolPatch.encode(linker);
      this.nameAndType = nameAndType;
      this.constantPoolPatch = constantPoolPatch;
      this.varMap = new HashMap<>();
      this.bindingMap = bindingMap;
    }
    
    String encodeDeopt(MethodHandle mh, Object o) {
      return constantPoolPatch.encode(MethodHandles.insertArguments(mh, 0, linker, nameAndType, o));
    }
    String encodeConst(Object constant) {
      return constantPoolPatch.encode(constant);
    }
  }
  
  static class Var extends com.github.forax.vmboiler.Var {
    Binding binding;

    Var(Type type, String name, int slot) {
      super(type, name, slot);
    }
  }
  
  private static final Constant NULL = new Constant(Type.OBJECT, null);
  
  public static MethodHandle generate(Fn fn, Linker linker, HashMap<Expr, Binding> bindingMap, Type returnType, String name, Type[] parameterTypes) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
    writer.visit(V1_8, ACC_PUBLIC|ACC_SUPER, "Fn", null, "java/lang/Object", null);
    ConstantPoolPatch constantPoolPatch = new ConstantPoolPatch(writer);
    
    String desc = Arrays.stream(parameterTypes).map(type -> type.asmType().getDescriptor()).collect(Collectors.joining("", "(", ")"))
        + returnType.asmType().getDescriptor();
    MethodVisitor mv = writer.visitMethod(ACC_PUBLIC|ACC_STATIC, name, desc, null, null);
    CodeGen codeGen = new CodeGen(mv, returnType, parameterTypes, fn.parameters().stream().map(Parameter::name).toArray(String[]::new),
        (type, n, slot) -> new Var((Type)type, n, slot));
    mv.visitCode();
    
    Env env = new Env(codeGen, linker, name + desc, constantPoolPatch, bindingMap);
    for(int i = 0; i < codeGen.parameterCount(); i++) {
      Binding binding = bindingMap.get(fn.parameters().get(i));
      Var parameterVar = (Var)codeGen.parameterVar(i);
      Var var;
      if (binding.type() != parameterVar.type()) {
        var = createVar(codeGen, binding.type(), parameterVar.name(), null);
        convert(var, parameterVar, env);
      } else {
        var = parameterVar;
      }
      var.binding = binding;
      env.varMap.put(binding, var);
    }
    
    Value value = VISITOR.call(fn.block(), env);
    
    if (returnType == value.type() || returnType.erase() == value.type()) {
      env.codeGen.ret(value);
    } else {
      Var var = createVar(codeGen, returnType, null, null);
      convert(var, value, env);
      env.codeGen.ret(var);
    }
    
    codeGen.end();
    mv.visitMaxs(-1, -1);
    mv.visitEnd();
    writer.visitEnd();
    
    byte[] bytecode = writer.toByteArray();
    
    CheckClassAdapter.verify(new ClassReader(bytecode), true, new PrintWriter(System.out));
    /*try {
      Files.write(Paths.get(name + ".class"), bytecode);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }*/
    
    Class<?> clazz = UNSAFE.defineAnonymousClass(RT.class, bytecode, constantPoolPatch.createPatchArray());
    UNSAFE.ensureClassInitialized(clazz);
    try {
      return MethodHandles.publicLookup().findStatic(clazz, name, MethodType.fromMethodDescriptorString(desc, null));
    } catch (NoSuchMethodException | IllegalAccessException | TypeNotPresentException e) {
      throw new AssertionError(e);
    }
  }
  
  private static final Unsafe UNSAFE;
  static {
    try {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      UNSAFE = (Unsafe)field.get(null);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
  
  private static Var createVar(CodeGen codeGen, Type type, String name, Binding binding) {
    Var var = (Var)codeGen.createVar(type, name);
    var.binding = binding;
    return var;
  }
  
  private static void convert(Var var, Value value, Env env) {
    if (var.type() == value.type() || ((Type)var.type()).erase() == value.type()) {
      env.codeGen.move(var, value);
    } else {
      Binding[] bindings = new Binding[] { (value instanceof Var)? ((Var)value).binding: null };
      env.codeGen.call(BSM_CONVERT, EMPTY_ARRAY,
          env.encodeDeopt(RT.DEOPT_ARGS, bindings),
          env.encodeDeopt(RT.DEOPT_RETURN, var.binding),
          var, "convert", value);
    }
  }
  
  private static final Object[] EMPTY_ARRAY = new Object[0];
  
  private static final String RT_NAME = RT.class.getName().replace('.', '/');
  private static final Handle BSM = new Handle(H_INVOKESTATIC, RT_NAME, "bsm",
      MethodType.methodType(CallSite.class, Lookup.class, String.class, MethodType.class, Linker.class).toMethodDescriptorString());
  private static final Handle BSM_CONVERT = new Handle(H_INVOKESTATIC, RT_NAME, "bsm_convert",
      MethodType.methodType(CallSite.class, Lookup.class, String.class, MethodType.class).toMethodDescriptorString());
  private static final Handle BSM_OP = new Handle(H_INVOKESTATIC, RT_NAME, "bsm_op",
      MethodType.methodType(CallSite.class, Lookup.class, String.class, MethodType.class, Linker.class).toMethodDescriptorString());
  
  private static final Visitor<Value, Env> VISITOR = new Visitor<Value, Env>()
      .when(Literal.class, (literal, env) -> {
        Object constant = literal.constant();
        if (constant instanceof Integer) {
          return new Constant(Type.INT, constant);
        }
        if (constant instanceof BigInteger) {
          return new Constant(Type.OBJECT, env.encodeConst(constant));
        }
        return new Constant(Type.OBJECT, constant);
      })
      .when(Block.class, (block, env) -> {
        Value value = NULL;
        for(Expr expr: block.exprs()) {
          value = Generator.VISITOR.call(expr, env);
        }
        return value;
      })
      .when(VarAccess.class, (varAccess, env) -> {
        Binding binding = env.bindingMap.get(varAccess);
        return env.varMap.get(binding);
      })
      .when(VarAssignment.class, (varAssignment, env) -> {
        Value value = Generator.VISITOR.call(varAssignment.expr(), env);
        Binding binding = env.bindingMap.get(varAssignment);
        Var var = env.varMap.get(binding);
        if (var == null) {
          var = (Var)env.codeGen.createVar(binding.type(), varAssignment.name());
          env.varMap.put(binding, var);
        }
        convert(var, value, env);
        return value;
      })
      .when(Call.class, (call, env) -> {
        Binding binding = env.bindingMap.get(call);
        Value[] values = call.exprs().stream().map(expr -> Generator.VISITOR.call(expr, env)).toArray(Value[]::new);
        Binding[] bindings = Arrays.stream(values).map(value -> (value instanceof Var)? ((Var)value).binding: null).toArray(Binding[]::new);
        Var rVar = createVar(env.codeGen, binding.type(), null, binding);
        Handle bsm = call.optionalOp().map(op -> BSM_OP).orElse(BSM);
        
        // <HACK> make call to fibo explicit
        /*if ((call.name() + "(I)I").equals(env.nameAndType)) {
          env.codeGen.methodVisitor().visitVarInsn(ILOAD, 1 + ((Var)values[0]).slot());
          env.codeGen.methodVisitor().visitMethodInsn(INVOKESTATIC, "Fn", call.name(), "(I)I", false);
          env.codeGen.methodVisitor().visitVarInsn(ISTORE, 1 + rVar.slot());
          env.codeGen.methodVisitor().visitFieldInsn(GETSTATIC, "com/github/forax/vmboiler/rt/RT", "NONE", "Ljava/lang/Object;");
          env.codeGen.methodVisitor().visitVarInsn(ASTORE, rVar.slot());
          return rVar;
        }*/
        // </HACK>
        
        env.codeGen.call(bsm, new Object[] { env.linkerPlaceholder }, env.encodeDeopt(RT.DEOPT_ARGS, bindings), env.encodeDeopt(RT.DEOPT_RETURN, binding),
            rVar, call.name(), values);
        return rVar;
      })
      .when(If.class, (if_, env) -> {
        Binding binding = env.bindingMap.get(if_);
        Value value = Generator.VISITOR.call(if_.condition(), env);
        Label label = new Label();
        Label end = new Label();
        Var rVar = createVar(env.codeGen, binding.type(), null, binding);
        env.codeGen.jumpIfFalse(value, label);
        Value value1 = Generator.VISITOR.call(if_.truePart(), env);
        convert(rVar, value1, env);
        env.codeGen.jump(end);
        env.codeGen.label(label);
        Value value2 = Generator.VISITOR.call(if_.falsePart(), env);
        convert(rVar, value2, env);
        env.codeGen.label(end);
        return rVar;
      })
      .when(While.class, (while_, env) -> {
        Label test = new Label();
        Label loop = new Label();
        env.codeGen.jump(test);
        env.codeGen.label(loop);
        Generator.VISITOR.call(while_.body(), env);
        env.codeGen.label(test);
        Value result = Generator.VISITOR.call(while_.condition(), env);
        env.codeGen.jumpIfTrue(result, loop);
        return NULL;
      })
      ;
}
