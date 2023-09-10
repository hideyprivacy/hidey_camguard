package com.example.hideycamguard

import android.graphics.*
import android.media.Image
import androidx.core.math.MathUtils
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImageUtil {
    companion object {

        fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 100): ByteArray {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            return outputStream.toByteArray()
        }

        fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
            val width = bitmap.width
            val height = bitmap.height
            val size = width * height
            val intValues = IntArray(size)
            val floatValues = FloatArray(size * 3) // Assuming RGB format

            bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

            for (i in 0 until intValues.size) {
                val value = intValues[i]
                floatValues[i * 3] = ((value shr 16 and 0xFF) / 255.0f) // R
                floatValues[i * 3 + 1] = ((value shr 8 and 0xFF) / 255.0f) // G
                floatValues[i * 3 + 2] = ((value and 0xFF) / 255.0f) // B
            }

            val byteBuffer = ByteBuffer.allocateDirect(4 * floatValues.size)
            byteBuffer.order(ByteOrder.nativeOrder())
            byteBuffer.asFloatBuffer().put(floatValues)
            return byteBuffer
        }

        fun yuv420ToBitmap(image: Image): Bitmap {
            require(image.format == ImageFormat.YUV_420_888) { "Invalid image format" }
            val imageWidth = image.width
            val imageHeight = image.height
            // ARGB array needed by Bitmap static factory method I use below.
            val argbArray = IntArray(imageWidth * imageHeight)
            val yBuffer = image.planes[0].buffer
            yBuffer.position(0)

            // A YUV Image could be implemented with planar or semi planar layout.
            // A planar YUV image would have following structure:
            // YYYYYYYYYYYYYYYY
            // ................
            // UUUUUUUU
            // ........
            // VVVVVVVV
            // ........
            //
            // While a semi-planar YUV image would have layout like this:
            // YYYYYYYYYYYYYYYY
            // ................
            // UVUVUVUVUVUVUVUV   <-- Interleaved UV channel
            // ................
            // This is defined by row stride and pixel strides in the planes of the
            // image.

            // Plane 1 is always U & plane 2 is always V
            // https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888
            val uBuffer = image.planes[1].buffer
            uBuffer.position(0)
            val vBuffer = image.planes[2].buffer
            vBuffer.position(0)

            // The U/V planes are guaranteed to have the same row stride and pixel
            // stride.
            val yRowStride = image.planes[0].rowStride
            val yPixelStride = image.planes[0].pixelStride
            val uvRowStride = image.planes[1].rowStride
            val uvPixelStride = image.planes[1].pixelStride
            var r: Int
            var g: Int
            var b: Int
            var yValue: Int
            var uValue: Int
            var vValue: Int
            for (y in 0 until imageHeight) {
                for (x in 0 until imageWidth) {
                    val yIndex = y * yRowStride + x * yPixelStride
                    // Y plane should have positive values belonging to [0...255]
                    yValue = yBuffer[yIndex].toInt() and 0xff
                    val uvx = x / 2
                    val uvy = y / 2
                    // U/V Values are subsampled i.e. each pixel in U/V chanel in a
                    // YUV_420 image act as chroma value for 4 neighbouring pixels
                    val uvIndex = uvy * uvRowStride + uvx * uvPixelStride

                    // U/V values ideally fall under [-0.5, 0.5] range. To fit them into
                    // [0, 255] range they are scaled up and centered to 128.
                    // Operation below brings U/V values to [-128, 127].
                    uValue = (uBuffer[uvIndex].toInt() and 0xff) - 128
                    vValue = (vBuffer[uvIndex].toInt() and 0xff) - 128

                    // Compute RGB values per formula above.
                    r = (yValue + 1.370705f * vValue).toInt()
                    g = (yValue - 0.698001f * vValue - 0.337633f * uValue).toInt()
                    b = (yValue + 1.732446f * uValue).toInt()
                    r = MathUtils.clamp(r, 0, 255)
                    g = MathUtils.clamp(g, 0, 255)
                    b = MathUtils.clamp(b, 0, 255)

                    // Use 255 for alpha value, no transparency. ARGB values are
                    // positioned in each byte of a single 4 byte integer
                    // [AAAAAAAARRRRRRRRGGGGGGGGBBBBBBBB]
                    val argbIndex = y * imageWidth + x
                    argbArray[argbIndex] =
                        255 shl 24 or (r and 255 shl 16) or (g and 255 shl 8) or (b and 255)
                }
            }
            return Bitmap.createBitmap(argbArray, imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        }
    }
}