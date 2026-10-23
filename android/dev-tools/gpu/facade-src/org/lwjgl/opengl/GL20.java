// Facade shim — RLHD v1.6.0.1 reads GL20.GL_POINT_SPRITE (only when !forwardCompatible, which is never here).
package org.lwjgl.opengl;
public class GL20 extends GLALL {
	public static final int GL_POINT_SPRITE = 0x8861;
}
