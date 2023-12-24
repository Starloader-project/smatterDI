package org.stianloader.smatterdi;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import software.coley.cafedude.InvalidClassException;
import software.coley.cafedude.classfile.AttributeConstants;
import software.coley.cafedude.classfile.ClassFile;
import software.coley.cafedude.classfile.ConstPool;
import software.coley.cafedude.classfile.Field;
import software.coley.cafedude.classfile.Modifiers;
import software.coley.cafedude.classfile.VersionConstants;
import software.coley.cafedude.classfile.attribute.Attribute;
import software.coley.cafedude.classfile.attribute.CodeAttribute;
import software.coley.cafedude.classfile.attribute.StackMapTableAttribute;
import software.coley.cafedude.classfile.attribute.StackMapTableAttribute.SameFrame;
import software.coley.cafedude.classfile.attribute.StackMapTableAttribute.StackMapFrame;
import software.coley.cafedude.classfile.constant.CpClass;
import software.coley.cafedude.classfile.constant.CpEntry;
import software.coley.cafedude.classfile.constant.CpFieldRef;
import software.coley.cafedude.classfile.constant.CpInterfaceMethodRef;
import software.coley.cafedude.classfile.constant.CpMethodRef;
import software.coley.cafedude.classfile.constant.CpNameType;
import software.coley.cafedude.classfile.constant.CpUtf8;
import software.coley.cafedude.classfile.instruction.BasicInstruction;
import software.coley.cafedude.classfile.instruction.CpRefInstruction;
import software.coley.cafedude.classfile.instruction.Instruction;
import software.coley.cafedude.classfile.instruction.IntOperandInstruction;
import software.coley.cafedude.classfile.instruction.Opcodes;
import software.coley.cafedude.io.ClassFileWriter;

/**
 * Implementation of the {@link ObjectAllocator} interface using
 * CafeDude to define wrapper classes.
 */
public abstract class CDObjectAllocator implements ObjectAllocator {

    private static final AtomicLong GENERATED_CLASS_COUNTER = new AtomicLong();

    private static interface Allocator<T> {
        @NotNull
        T allocate(InjectionContext ctx,  Object... args);
    }

    private final Map<Class<?>, Allocator<?>> caches = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    @NotNull
    public <T> T allocate(@NotNull Class<T> type, @NotNull InjectionContext injectCtx, Object... args) {
        Allocator<?> cachedAllocator = this.caches.get(type);
        if (cachedAllocator != null) {
            return (T) cachedAllocator.allocate(injectCtx, args);
        }

        if (type.getSuperclass() == null || type.isArray()) {
            throw new UnsupportedOperationException("Cannot create an instance of class " + type + ". It is likely an interface or any other type which cannot be initialized in this way.");
        }

        synchronized (this) {
            Allocator<T> allocator = this.createAllocator(type);
            this.caches.put(type, allocator);
            return allocator.allocate(injectCtx, args);
        }
    }

    @SuppressWarnings({ "unchecked", "null" })
    @NotNull
    private <T> Allocator<T> createReflectionAllocator(@NotNull Class<T> clazz) {
        return (ctx, args) -> {
            List<Constructor<T>> candidateCtors = new ArrayList<>();
            constructorloop:
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                Class<?>[] c = ctor.getParameterTypes();
                if (c.length != args.length) {
                    continue;
                }
                for (int i = 0; i < args.length; i++) {
                    if (c[i].isPrimitive()) {
                        if (!(args[i] instanceof Number || (args[i] instanceof Character && c[i] == char.class))) {
                            continue constructorloop;
                        }
                    } else if (args[i] != null && !c[i].isInstance(args[i])) {
                        continue constructorloop;
                    }
                }
                candidateCtors.add((Constructor<T>) ctor);
            }
            if (candidateCtors.size() != 1) {
                throw new UnsupportedOperationException("No constructors apply for argument array " + Arrays.stream(args).map((x) -> {
                    if (x == null) {
                        return "(any)";
                    } else {
                        if (x instanceof Number) {
                            return x.getClass().descriptorString() + " (or primitive of that type)";
                        } else if (x instanceof Character) {
                            return "(char or java.lang.Character)";
                        }
                        return x.getClass().descriptorString();
                    }
                }).collect(ArrayList::new, ArrayList::add, ArrayList::addAll) + ", found " + candidateCtors.size());
            }
            try {
                return candidateCtors.get(0).newInstance(args);
            } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException e) {
                throw new IllegalStateException("Unable to call constructor of class " + clazz + ".", e);
            }
        };
    }

    private static CpClass lazyClass(Map<String, CpClass> m, Map<String, CpUtf8> s, String key) {
        return m.compute(key, (k, oldVal) -> {
            if (oldVal != null) {
                return oldVal;
            } else {
                return new CpClass(s.compute(k, CDObjectAllocator::lazyUtf8));
            }
        });
    }

    @NotNull
    private static CpUtf8 lazyUtf8(String key, CpUtf8 val) {
        if (val != null) {
            return val;
        } else {
            return new CpUtf8(key);
        }
    }

    @NotNull
    private <T> Allocator<T> createAllocator(@NotNull Class<T> clazz) {
        boolean doAutowire = clazz.isAnnotationPresent(Autowire.class);

        List<Method> injectMethods = new ArrayList<>();
        for (Class<?> c = clazz; c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Inject.class)) {
                    injectMethods.add(m);
                }
            }
        }

        if (!doAutowire && injectMethods.isEmpty()) {
            return createReflectionAllocator(clazz);
        }

        Set<Map.Entry<String, String>> injects = new LinkedHashSet<>();
        for (Method m : injectMethods) {
            if (m.getParameters().length != 0) {
                throw new IllegalStateException("Class " + clazz.getName() + " is invalid; an @Inject-annotated method may not have any parameters! Method " + m.getName() + " has though!");
            }
            if (m.getReturnType().isPrimitive() || m.getReturnType() == void.class) {
                throw new IllegalStateException("Class " + clazz.getName() + " is invalid; an @Inject-annotated method must return a non-primitive, non-void type! Method " + m.getName() + " doesn't though!");
            }
            injects.add(Map.entry(m.getName(), "()" + m.getReturnType().descriptorString()));
        }

        ConstPool cp = new ConstPool();
        String name = clazz.getPackageName().replace('.', '/') + "/generated_" + clazz.getSimpleName() + "_" + GENERATED_CLASS_COUNTER.getAndIncrement();
        CpUtf8 nameUtf8 = new CpUtf8(name);
        CpClass nameClass = new CpClass(nameUtf8);
        cp.add(nameUtf8);
        cp.add(nameClass);
        CpUtf8 superUtf8 = new CpUtf8(clazz.getName().replace('.', '/'));
        CpClass superClass = new CpClass(superUtf8);
        cp.add(superUtf8);
        cp.add(superClass);

        List<Field> fields = new ArrayList<>();

        String context = "generated_context_" + System.currentTimeMillis();
        CpUtf8 contextUtf8 = new CpUtf8(context);
        CpUtf8 contextTypeUtf8 = new CpUtf8("Lorg/stianloader/smatterdi/InjectionContext;");
        cp.add(contextUtf8);
        cp.add(contextTypeUtf8);
        fields.add(new Field(Collections.emptyList(), Modifiers.ACC_FINAL | Modifiers.ACC_PUBLIC | Modifiers.ACC_SYNTHETIC, contextUtf8, contextTypeUtf8));

        List<software.coley.cafedude.classfile.Method> methods = new ArrayList<>();

        CpNameType contextNameType = new CpNameType(contextUtf8, contextTypeUtf8);
        CpFieldRef contextField = new CpFieldRef(nameClass, contextNameType);
        cp.add(contextField);
        cp.add(contextNameType);

        Map<String, CpUtf8> names = new LinkedHashMap<>();
        Map<String, CpClass> types = new LinkedHashMap<>();
        Map<String, CpNameType> constructorNTCache = new LinkedHashMap<>();
        List<CpEntry> otherEntries = new ArrayList<>();

        CpUtf8 codeUtf8 = names.compute(AttributeConstants.CODE, CDObjectAllocator::lazyUtf8);
        CpUtf8 constructorNameUtf8 = names.compute("<init>", CDObjectAllocator::lazyUtf8);
        CpClass contextClass = lazyClass(types, names, "org/stianloader/smatterdi/InjectionContext");
        CpUtf8 contextLookupNameUtf8 = names.compute("getInstance", CDObjectAllocator::lazyUtf8);
        CpUtf8 contextLookupDescUtf8 = names.compute("(Ljava/lang/Class;)Ljava/lang/Object;", CDObjectAllocator::lazyUtf8);
        CpNameType contextLookupNT = new CpNameType(contextLookupNameUtf8, contextLookupDescUtf8);
        CpInterfaceMethodRef contextLookup = new CpInterfaceMethodRef(contextClass, contextLookupNT);

        CpNameType contextAutowireNT = null;
        CpInterfaceMethodRef contextAutowire = null;
        CpNameType autowireBoolFieldNT = null;
        CpFieldRef autowireBoolField = null;
        CpNameType autowireMethodNT = null;
        CpMethodRef autowireMethod = null;

        if (doAutowire) {
            CpUtf8 autowireBoolName = names.compute("generated_autowired_" + System.currentTimeMillis(), CDObjectAllocator::lazyUtf8);
            CpUtf8 autowireBoolType = names.compute("Z", CDObjectAllocator::lazyUtf8);
            CpUtf8 contextAutowireNameUtf8 = names.compute("autowire", CDObjectAllocator::lazyUtf8);
            CpUtf8 contextAutowireDescUtf8 = names.compute("(Ljava/lang/Class;Ljava/lang/Object;)V", CDObjectAllocator::lazyUtf8);
            CpUtf8 voidNoArgsMethodUtf8 = names.compute("()V", CDObjectAllocator::lazyUtf8);
            CpUtf8 stackMapAttributeNameUtf8 = names.compute(AttributeConstants.STACK_MAP_TABLE, CDObjectAllocator::lazyUtf8);
            contextAutowireNT = new CpNameType(contextAutowireNameUtf8, contextAutowireDescUtf8);
            contextAutowire = new CpInterfaceMethodRef(contextClass, contextAutowireNT);
            autowireBoolFieldNT = new CpNameType(autowireBoolName, autowireBoolType);
            autowireBoolField = new CpFieldRef(nameClass, autowireBoolFieldNT);
            autowireMethodNT = new CpNameType(autowireBoolName, voidNoArgsMethodUtf8);
            autowireMethod = new CpMethodRef(nameClass, autowireMethodNT);
            fields.add(new Field(Collections.emptyList(), Modifiers.ACC_PRIVATE | Modifiers.ACC_SYNTHETIC, autowireBoolName, autowireBoolType));
            List<Attribute> methodAttrs = new ArrayList<>();
            List<Instruction> insns = new ArrayList<>();
            insns.add(new BasicInstruction(Opcodes.ALOAD_0));
            insns.add(new CpRefInstruction(Opcodes.GETFIELD, autowireBoolField));
            insns.add(new IntOperandInstruction(Opcodes.IFEQ, 4)); // Skip 4 byte (3 bytes IFEQ, 1 byte RETURN) if == 0
            insns.add(new BasicInstruction(Opcodes.RETURN));
            insns.add(new BasicInstruction(Opcodes.ALOAD_0));
            insns.add(new BasicInstruction(Opcodes.ICONST_1));
            insns.add(new CpRefInstruction(Opcodes.PUTFIELD, autowireBoolField));
            insns.add(new BasicInstruction(Opcodes.ALOAD_0));
            insns.add(new CpRefInstruction(Opcodes.GETFIELD, contextField));
            insns.add(new CpRefInstruction(Opcodes.LDC, superClass));
            insns.add(new BasicInstruction(Opcodes.ALOAD_0));
            insns.add(new CpRefInstruction(Opcodes.INVOKEINTERFACE, contextAutowire));
            insns.add(new BasicInstruction(Opcodes.RETURN));
            List<StackMapFrame> frames = new ArrayList<>();
            frames.add(new SameFrame(8)); // 1 ALOAD_0 + 3 GETFIELD + 3 IFEQ + 1 RETURN = 8
            methodAttrs.add(new CodeAttribute(codeUtf8, 3, 1, insns, Collections.emptyList(), List.of(new StackMapTableAttribute(stackMapAttributeNameUtf8, frames))));
            methods.add(new software.coley.cafedude.classfile.Method(methodAttrs, Modifiers.ACC_SYNTHETIC | Modifiers.ACC_PRIVATE | Modifiers.ACC_FINAL, autowireBoolName, voidNoArgsMethodUtf8));
        }

        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            String descriptor = "";
            List<String> args = new ArrayList<>();
            for (Class<?> param : ctor.getParameterTypes()) {
                descriptor += param.descriptorString();
                args.add(param.descriptorString());
            }
            descriptor += ")V";
            String descriptorSuper = "(" + descriptor;
            descriptor = "(Lorg/stianloader/smatterdi/InjectionContext;" + descriptor;
            CpUtf8 descutf8 = names.compute(descriptor, CDObjectAllocator::lazyUtf8);

            List<Attribute> methodAttrs = new ArrayList<>();
            List<Instruction> insns = new ArrayList<>();
            int locals = 2;
            insns.add(new BasicInstruction(Opcodes.ALOAD_0));
            insns.add(new BasicInstruction(Opcodes.ALOAD_1));
            insns.add(new CpRefInstruction(Opcodes.PUTFIELD, contextField));
            insns.add(new BasicInstruction(Opcodes.ALOAD_0));
            for (String arg : args) {
                int ocode;
                boolean wide = false;
                switch (arg.charAt(0)) {
                case 'D':
                    ocode = Opcodes.DLOAD;
                    wide = true;
                    break;
                case 'J':
                    ocode = Opcodes.LLOAD;
                    wide = true;
                    break;
                case 'F':
                    ocode = Opcodes.FLOAD;
                    break;
                case 'I':
                case 'B':
                case 'S':
                case 'C':
                    ocode = Opcodes.ILOAD;
                    break;
                case 'L':
                case '[':
                    ocode = Opcodes.ALOAD;
                    break;
                default:
                    throw new IllegalStateException("Unexpected type: " + arg);
                }
                insns.add(new IntOperandInstruction(ocode, locals));
                locals += wide ? 2 : 1;
            }
            CpNameType ctorNT = constructorNTCache.compute(descriptorSuper, (ignore, val) -> {
                if (val != null) {
                    return val;
                }
                return new CpNameType(constructorNameUtf8, names.compute(descriptorSuper, CDObjectAllocator::lazyUtf8));
            });
            CpMethodRef mref = new CpMethodRef(superClass, ctorNT);
            otherEntries.add(mref);
            insns.add(new CpRefInstruction(Opcodes.INVOKESPECIAL, mref));
            if (doAutowire) {
                insns.add(new BasicInstruction(Opcodes.ALOAD_0));
                insns.add(new CpRefInstruction(Opcodes.INVOKEVIRTUAL, autowireMethod));
            }
            insns.add(new BasicInstruction(Opcodes.RETURN));
            methodAttrs.add(new CodeAttribute(codeUtf8, Math.max(3, locals), Math.max(2, locals), insns, Collections.emptyList(), Collections.emptyList()));
            methods.add(new software.coley.cafedude.classfile.Method(methodAttrs, Modifiers.ACC_PUBLIC, constructorNameUtf8, descutf8));
        }

        for (Map.Entry<String, String> e : injects) {
            CpUtf8 nutf8 = names.compute(e.getKey(), CDObjectAllocator::lazyUtf8);
            CpUtf8 descutf8 = names.compute(e.getValue(), CDObjectAllocator::lazyUtf8);
            List<Attribute> methodAttrs = new ArrayList<>();
            CpClass returnType = lazyClass(types, names, e.getValue().substring(3, e.getValue().length() - 1).replace('.', '/'));
            List<Instruction> insns = new ArrayList<>();
            if (doAutowire) {
                insns.add(new BasicInstruction(Opcodes.ALOAD_0));
                insns.add(new CpRefInstruction(Opcodes.INVOKEVIRTUAL, autowireMethod));
            }
            insns.add(new BasicInstruction(Opcodes.ALOAD_0));
            insns.add(new CpRefInstruction(Opcodes.GETFIELD, contextField));
            insns.add(new CpRefInstruction(Opcodes.LDC_W, returnType));
            insns.add(new CpRefInstruction(Opcodes.INVOKEINTERFACE, contextLookup));
            insns.add(new CpRefInstruction(Opcodes.CHECKCAST, returnType));
            insns.add(new BasicInstruction(Opcodes.ARETURN));
            methodAttrs.add(new CodeAttribute(codeUtf8, 2, 1, insns, Collections.emptyList(), Collections.emptyList()));
            methods.add(new software.coley.cafedude.classfile.Method(methodAttrs, Modifiers.ACC_FINAL | Modifiers.ACC_PUBLIC | Modifiers.ACC_SYNTHETIC, nutf8, descutf8));
        }

        cp.addAll(names.values());
        cp.addAll(types.values());
        cp.addAll(constructorNTCache.values());
        cp.addAll(otherEntries);
        cp.add(contextLookupNT);
        cp.add(contextLookup);

        if (doAutowire) {
            cp.add(contextAutowireNT);
            cp.add(contextAutowire);
            cp.add(autowireBoolFieldNT);
            cp.add(autowireBoolField);
            cp.add(autowireMethodNT);
            cp.add(autowireMethod);
        }

        ClassFile cf = new ClassFile(0, VersionConstants.JAVA8, cp, Modifiers.ACC_PUBLIC | Modifiers.ACC_FINAL, nameClass, superClass, Collections.emptyList(), fields, methods, Collections.emptyList());

        try {
            byte[] data = new ClassFileWriter().write(cf);
            if (Boolean.getBoolean("org.stianloader.smatterdi.debug")) {
                Path p = Path.of("smatterdi-generated", cf.getName() + ".class");
                Path parent = p.getParent();
                if (parent == null) {
                    throw new AssertionError();
                }
                try {
                    Files.createDirectories(parent);
                    Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    LoggerFactory.getLogger(getClass()).error("Unable to write generated class bytes", e);
                }
            }
            @SuppressWarnings("null")
            Allocator<?> forwardAllocator = this.createReflectionAllocator(this.defineClass(name, data, clazz));
            return (ctx, args) -> {
                Object[] realArgs = new Object[args.length + 1];
                realArgs[0] = ctx;
                System.arraycopy(args, 0, realArgs, 1, args.length);
                @SuppressWarnings("unchecked")
                T allocated = (T) forwardAllocator.allocate(ctx, realArgs);
                return allocated;
            };
        } catch (InvalidClassException e1) {
            throw new UnsupportedOperationException("Unable to define injection wrapper class '" + name + "'.", e1);
        }
    }

    @NotNull
    public abstract Class<?> defineClass(@NotNull String className, @NotNull byte[] data, @NotNull Class<?> superClass);
}
