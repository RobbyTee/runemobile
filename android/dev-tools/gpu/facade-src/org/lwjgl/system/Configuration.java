package org.lwjgl.system;
public class Configuration<T> {
	public static final Configuration<String> SHARED_LIBRARY_EXTRACT_DIRECTORY = new Configuration<>();
	// Set in OpenCLManager.<clinit> (the only thing that runs at class-init on the Zone path).
	public static final Configuration<Boolean> OPENCL_EXPLICIT_INIT = new Configuration<>();
	public Configuration<T> set(T value) { return this; }
	public T get() { return null; }
}
