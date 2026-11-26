// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// 版本号集中在这里：既进 APK 元数据，也进产物文件名
val appVersionCode = 140
val appVersionName = "0.9.88-M2"

android {
  namespace = "com.dsh.starpos"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.dsh.starpos"
    minSdk = 30          // 需求 NFR-05：Camera2 / 建议 API 30+
    targetSdk = 37
    versionCode = appVersionCode
    versionName = appVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // 发布签名不写进仓库：通过环境变量提供（见 README「发布构建」）。
  val releaseStore = System.getenv("STARPOS_RELEASE_STORE_FILE")
  val hasReleaseSigning = !releaseStore.isNullOrBlank()

  signingConfigs {
    // 现场测试用：没有发布密钥时，release 仍用 debug 签名，方便直接安装（不是正式发布包）
    create("field") {
      storeFile = File(System.getenv("ANDROID_USER_HOME") ?: (System.getProperty("user.home") + "/.android"), "debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    create("release") {
      if (hasReleaseSigning) {
        storeFile = file(releaseStore!!)
        storePassword = System.getenv("STARPOS_RELEASE_STORE_PASSWORD")
        keyAlias = System.getenv("STARPOS_RELEASE_KEY_ALIAS")
        keyPassword = System.getenv("STARPOS_RELEASE_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("field")
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

// 默认只跑"快测试"（算法/回归）；field-inbox 全量分析等重测试用 -PfullAnalysis=true 才跑。
// 背景：FieldInboxReportTest 要分析 74 个包（≈400 s），加上批量汇总/精化测试，全量一轮要 16-17 分钟。
tasks.withType<Test>().configureEach {
  val fullAnalysis = (providers.gradleProperty("fullAnalysis").orNull ?: "false").toBoolean()
  if (!fullAnalysis) {
    exclude(
      "**/FieldInboxReportTest.class",
      "**/MoonJointFixWithMatrixTest.class",
      "**/SessionSummaryTest.class",
      "**/BatchSummaryTest.class",
      "**/DawnCalibTest.class",
      // 诊断/自标定/深度分析类（跑全量真机数据，几分钟到几十分钟）：
      // 手动用 --tests 或 -PfullAnalysis=true 单独跑
      "**/HandheldImuImpactTest.class",
      "**/JointCalibTest.class",
      "**/LensSelfCalibTest.class",
      "**/StarZenithTest.class",
      "**/AccelMapCalibTest.class",
      "**/AccelMapValidateTest.class",
      "**/ExtrinsicFromTwoBodyTest.class",
      "**/AccelBiasValidateTest.class",
      "**/ExtrinsicRefitValidateTest.class",
      "**/TwoBodyMultiSessionTest.class",
      "**/TwoBodyHdrTest.class",
      "**/TwoBodySessionTest.class",
      "**/MoonSaturnPairTest.class",
      "**/SunSessionDiagTest.class",
      "**/SunAnalyzerTest.class",
      "**/HorizonSessionTest.class",
      "**/FullFieldCsvTest.class",
      "**/WideAngleDebugTest.class",
      "**/WaterMirrorDiagTest.class",
      "**/VerticalDatumEvalTest.class",
      "**/VerticalCeilingTest.class",
      "**/VerticalRevalidateTest.class",
      "**/VerticalSunMoonHoldoutTest.class",
      "**/StarVerticalApplyTest.class",
      "**/StarVerticalGridTest.class",
      "**/J2016EpochTest.class",
      "**/JointSolverSunTest.class",
      "**/DebugStar003438Test.class",
      "**/WideAngleProdTest.class",
      "**/WideExtrinsicFitTest.class",
    )
    maxParallelForks = 2
  }
}

// 输出文件名带版本号：starpos-<versionName>-<buildType>.apk（如 starpos-0.2.3-M2-debug.apk）
androidComponents {
  onVariants { variant ->
    variant.outputs.forEach { output ->
      output.outputFileName.set("starpos-$appVersionName-${variant.buildType}.apk")
    }
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  val composeBom = platform(libs.compose.bom)
  implementation(composeBom)
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.material3)
  debugImplementation(libs.compose.ui.tooling)

  // 线性代数（Wahba/SVD、协方差特征分解）：算法层保持平台无关
  implementation(libs.commons.math3)

  testImplementation(libs.junit)
  testImplementation(libs.commons.math3)
}


















































































































