/*
 * Copyright 2023-2025 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

rootProject.name = "MobileFuseAdapter"
include(":MobileFuseAdapter")
include(":android-helium-sdk")
include(":ChartboostMediation")
