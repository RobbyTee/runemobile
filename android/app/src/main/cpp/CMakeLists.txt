cmake_minimum_required(VERSION 3.22.1)
project(helloshell)

add_library(helloshell SHARED jvm_bootstrap.cpp)

find_library(log-lib log)
find_library(android-lib android)

# We resolve JNI_CreateJavaVM from the embedded JRE's libjvm.so at runtime via dlopen, so we do NOT link
# it here. We only need Android's own libs (logging + ANativeWindow for the Milestone B surface blit).
target_link_libraries(helloshell ${log-lib} ${android-lib} dl)

# Track G AG1a — the librlawt EGL/GLES replacement: an Android EGL backing for the unmodified base gpu plugin's
# AWTContext + org.lwjgl.opengl facade (the per-ABI native drop-in; arm64-v8a only today). The host (helloshell)
# dlopen's it + calls RouteC_registerGLNatives. Links Android's native EGL + GLES v3 driver — no ANGLE, no gl4es.
add_library(rlawt SHARED librlawt.cpp)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)
target_link_libraries(rlawt ${log-lib} ${egl-lib} ${glesv3-lib})
