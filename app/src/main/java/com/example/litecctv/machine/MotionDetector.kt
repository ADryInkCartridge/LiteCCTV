package com.example.litecctv.machine

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.lang.Math.abs

class MotionDetector {
    private var lastImagePoint: Double = 0.0

    public fun hasMotion(newImage: Bitmap): Boolean {
        val newImagePoint: Double = getScoreFromBitmap(newImage)
        Log.i("MotionDetector", "Score is $newImagePoint")
        if (lastImagePoint == 0.0) {
            lastImagePoint = newImagePoint
        }
        else if (abs(lastImagePoint-newImagePoint) > ERROR_POINT) {
            lastImagePoint = newImagePoint
            return true
        }
        return false
    }

    private fun getScoreFromBitmap(bitmap: Bitmap): Double {
        val width: Int = bitmap.width
        val height: Int = bitmap.height

        var score: Double = 0.0

        for (y in 0..height-1 step 5) {
            for (x in 0..width-1 step 5) {
                val pixelColor = bitmap.getPixel(x, y)
                val redColor: Int = Color.red(pixelColor)
                score += redColor
            }
        }

        score /= (width*height/100)

        return score
    }

    companion object {
        val ERROR_POINT: Double = 14.80
    }
}