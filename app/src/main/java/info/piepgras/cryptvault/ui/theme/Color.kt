package info.piepgras.cryptvault.ui.theme

import androidx.compose.ui.graphics.Color

// The shared palette for the productivity apps, taken from Work Time Tracker's
// icon and feature graphic and adopted here on 2026-09-21, replacing the navy
// #1E3A8A that BUILD_BRIEF.md §13 row 22 had settled. It is a house rule, not a
// per-app choice: the same blues are expected in every app of this family, so an
// accent picked here would have to be picked in Flip Cards and Work Time Tracker
// too. CLAUDE.md carries the table.
//
// Light and dark are separate values on purpose. Material 3 wants a dark tone for
// primary against a light surface and a light tone against a dark one; one value
// used in both schemes is a contrast fault, not a simplification.
//
// The brand colour also drives the launcher icon background and the store listing —
// keep this, res/values/colors.xml, spec.json's theme_color and art/ic_launcher.svg
// in step. Brand is the bottom stop of the icon's gradient.
val Brand = Color(0xFF0D47A1)
val BrandDark = Color(0xFF90CAF9)
val BrandGrey = Color(0xFF455A64)
val BrandGreyDark = Color(0xFFB0BEC5)
val Accent = Color(0xFF00695C)
val AccentDark = Color(0xFF80CBC4)
