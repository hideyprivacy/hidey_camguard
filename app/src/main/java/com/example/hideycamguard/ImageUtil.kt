package com.example.hideycamguard

import android.graphics.*
import android.media.Image
import androidx.core.math.MathUtils
import org.opencv.core.CvType
import org.opencv.core.Mat
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

        fun matToByteBuffer(mat: Mat): ByteBuffer {

            val tempMat = Mat(mat.rows(), mat.cols(), CvType.CV_32F)
            mat.convertTo(tempMat, CvType.CV_32F, 1.0 / 255.0)  // Normalize

            val byteBuffer = ByteBuffer.allocateDirect(4 * tempMat.rows() * tempMat.cols() * tempMat.channels())  // float32 has 4 bytes
            byteBuffer.order(ByteOrder.nativeOrder())

            val floatArray = FloatArray(tempMat.rows() * tempMat.cols() * tempMat.channels())
            tempMat.get(0, 0, floatArray)

            byteBuffer.rewind()
            for (i in floatArray.indices) {
                byteBuffer.putFloat(floatArray[i])
            }

            return byteBuffer
        }


        fun bytesToBitmap(bytes: ByteArray): Bitmap {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

        fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
            val imageBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }

        fun imageToNV21(image: Image): ByteArray {
            val nv21: ByteArray
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            nv21 = ByteArray(ySize + uSize + vSize)

            // U and V are swapped
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            return nv21
        }
    }
}