package com.weekd.miracastreceiver

enum class ScaleMode(val label: String, val factor: Float?) {
    ORIGINAL("原始", null),
    ASPECT_FILL("填满", null),
    SCALE_125("125%", 1.25f),
    SCALE_150("150%", 1.50f),
    SCALE_175("175%", 1.75f),
    SCALE_200("200%", 2.00f),
    SCALE_250("250%", 2.50f)
}
