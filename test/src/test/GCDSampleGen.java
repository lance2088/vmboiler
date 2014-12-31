package test;

import static org.objectweb.asm.Opcodes.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.util.CheckClassAdapter;

import com.github.forax.vmboiler.CodeGen;
import com.github.forax.vmboiler.Type;
import com.github.forax.vmboiler.Var;

public class GCDSampleGen {
  public enum Types implements Type {
    INT, INT_MIXED, BOOL
    ;
    @Override
    public boolean isMixed() {
      return this == INT_MIXED;
    }
    @Override
    public String vmType() {
      return (this == BOOL)? Type.VM_BOOLEAN: Type.VM_INT;
    }
  }
  
  private static final Object[] EMPTY_ARRAY = new Object[0];
  
  private static final String GCD_SAMPLE_RT = GCDSampleRT.class.getName().replace('.', '/');
  private static final Handle BSM = new Handle(H_INVOKESTATIC,
      GCD_SAMPLE_RT, "bsm",
      MethodType.methodType(CallSite.class, Lookup.class, String.class, MethodType.class).toMethodDescriptorString());
  private static final Handle DEOPT_ARGS = new Handle(H_INVOKESTATIC,
      GCD_SAMPLE_RT, "deopt_args",
      MethodType.methodType(boolean.class, Object[].class).toMethodDescriptorString());
  private static final Handle DEOPT_RETURN = new Handle(H_INVOKESTATIC,
      GCD_SAMPLE_RT, "deopt_return",
      MethodType.methodType(boolean.class, Object.class).toMethodDescriptorString());
  
  
  public static void main(String[] args) throws IOException {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
    writer.visit(V1_8, ACC_PUBLIC|ACC_SUPER, "GCDSample", null, "java/lang/Object", null);
    MethodVisitor mv = writer.visitMethod(ACC_PUBLIC|ACC_STATIC, "gcd", "(II)I", null, null);
    mv.visitCode();
    CodeGen codeGen = new CodeGen(mv, Types.INT_MIXED,
        new Type[]   { Types.INT, Types.INT },
        new String[] { "x", "y"});
    Var x = codeGen.parameterVar(0);
    Var y = codeGen.parameterVar(1);
    Var a = codeGen.createVar(Types.INT_MIXED, "a");
    codeGen.move(a, x);
    Var b = codeGen.createVar(Types.INT_MIXED, "b");
    codeGen.move(b, y);
    Label loop = new Label();
    codeGen.label(loop);
    Var r0 = codeGen.createVar(Types.BOOL, null);
    codeGen.call(BSM, EMPTY_ARRAY, DEOPT_ARGS, DEOPT_RETURN,
        r0, "ne", a, b);
    Label end = new Label();
    codeGen.jumpIfFalse(r0, end);
    Var r1 = codeGen.createVar(Types.BOOL, null);
    codeGen.call(BSM, EMPTY_ARRAY, DEOPT_ARGS, DEOPT_RETURN,
        r1, "gt", a, b);
    Label otherwise = new Label();
    codeGen.jumpIfFalse(r1, otherwise);
    codeGen.call(BSM, EMPTY_ARRAY, DEOPT_ARGS, DEOPT_RETURN,
        a, "sub", a, b);
    codeGen.jump(loop);
    codeGen.label(otherwise);
    codeGen.call(BSM, EMPTY_ARRAY, DEOPT_ARGS, DEOPT_RETURN,
        b, "sub", b, a);
    codeGen.jump(loop);
    codeGen.label(end);
    codeGen.ret(a);
    codeGen.end();
    mv.visitMaxs(-1, -1);
    mv.visitEnd();
    
    MethodVisitor main = writer.visitMethod(ACC_PUBLIC|ACC_STATIC, "main",
        "([Ljava/lang/String;)V", null, null);
    main.visitCode();
    main.visitFieldInsn(GETSTATIC, "java/lang/System", "out",
        "Ljava/io/PrintStream;");
    main.visitLdcInsn(9);
    main.visitLdcInsn(6);
    main.visitMethodInsn(INVOKESTATIC, "GCDSample", "gcd", "(II)I", false);
    main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    main.visitInsn(RETURN);
    
    main.visitMaxs(-1, -1);
    main.visitEnd();
    
    writer.visitEnd();
    byte[] array = writer.toByteArray();
    
    ClassReader reader = new ClassReader(array);
    CheckClassAdapter.verify(reader, true, new PrintWriter(System.out));
    
    Files.write(Paths.get("GCDSample.class"), array);
  }
}
