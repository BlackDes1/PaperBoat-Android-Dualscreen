/**
 * The PAL game as a second native library, libmain_pal.so.
 *
 * The decomp compiles one region at a time (VERSION_US or VERSION_PAL change
 * code, data layout and asset paths), so the PAL game cannot share libmain.so.
 * This module runs the same CMake project with -DPAPERBOAT_REGION=pal and the
 * app packages both; MainActivity loads the one the player's ROM needs.
 *
 * Skip it for faster US-only builds: ./gradlew assembleDebug -Ppaperboat.pal=false
 */
plugins {
    id("com.android.library")
}

val repositoryRoot: File = rootProject.projectDir.parentFile

android {
    namespace = "dev.net64.paperboat.palcore"
    compileSdk = 36
    ndkVersion = "30.0.15729638"

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Keep in step with app/build.gradle.kts.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DUSE_OPENGLES=ON",
                    "-DSDL_SHARED=ON",
                    "-DSDL_STATIC=OFF",
                    "-DHAVE_LD_VERSION_SCRIPT=OFF",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DPAPERBOAT_REGION=pal"
                )
                targets += "Paperboat"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = File(repositoryRoot, "CMakeLists.txt")
            version = "3.30.3+"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
