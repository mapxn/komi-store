package zed.rainxch.apps.presentation.import.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
actual fun rememberSystemReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
