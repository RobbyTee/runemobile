cmake_minimum_required(VERSION 3.22.1)
project(helloshell)

add_library(helloshell SHARED jvm_bootstrap.cpp)

find_library(log-lib log)
find_library(android-lib android)

# We resolve JNI_CreateJavaVM from the embedded JRE's libjvm.so at runtime via dlopen, so we do NOT link
# it here. We only need Android's own libs (logging + ANativeWindow for the Milestone B surface blit).
target_link_libraries(helloshell ${log-lib} ${android-lib} dl)
