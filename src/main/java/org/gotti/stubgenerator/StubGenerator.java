package org.gotti.stubgenerator;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.NotFoundException;

public class StubGenerator {

	private static final String THROWING_BODY = "throw new java.lang.RuntimeException(\"Unimplemented\");";

	private final ClassPool classPool;


	public StubGenerator(ClassPool classPool) {
		this.classPool = classPool;
	}

	public synchronized CtClass makeStubClass(Class<?> clazz) throws NotFoundException, CannotCompileException {
		String classname = clazz.getName();
		CtClass ctclazz = produceStubClass(classPool.get(classname));
		return ctclazz;
	}
	
	public synchronized CtClass makeStubClass(CtClass ctClazz) throws NotFoundException, CannotCompileException {
		return produceStubClass(ctClazz);
	}
	
	private void setStubBodies(CtClass orgclass) throws NotFoundException, CannotCompileException {
		for (CtMethod method : orgclass.getDeclaredMethods()) {
			method.setBody(THROWING_BODY);
		}
		for (CtConstructor constructor : orgclass.getDeclaredConstructors()) {
			constructor.setBody(THROWING_BODY);
		}
	}

	private CtClass produceStubClass(CtClass orgclass) throws NotFoundException, CannotCompileException {
		setStubBodies(orgclass);
		return orgclass;
	}
}
