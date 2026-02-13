package com.qali.headline.util

import com.qali.headline.Mesh
import java.io.InputStream

object ObjLoader {
    fun parse(inputStream: InputStream): Mesh {
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.isEmpty()) return@forEach

                try {
                    when (parts[0]) {
                        "v" -> {
                            if (parts.size >= 4) {
                                val x = parts[1].toFloat()
                                val y = parts[2].toFloat()
                                val z = parts[3].toFloat()
                                vertices.add(x)
                                vertices.add(y)
                                vertices.add(z)

                                if (x < minX) minX = x; if (x > maxX) maxX = x
                                if (y < minY) minY = y; if (y > maxY) maxY = y
                                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                            }
                        }
                        "f" -> {
                            if (parts.size >= 4) {
                                val vIndices = mutableListOf<Short>()
                                for (i in 1 until parts.size) {
                                    val idx = parts[i].split("/")[0].toInt() - 1
                                    if (idx in 0..Short.MAX_VALUE.toInt()) {
                                        vIndices.add(idx.toShort())
                                    } else {
                                        // Ignore or handle high-index vertices
                                    }
                                }

                                if (vIndices.size >= 3) {
                                    // Fan triangulation for quads+
                                    for (i in 1 until vIndices.size - 1) {
                                        indices.add(vIndices[0])
                                        indices.add(vIndices[i])
                                        indices.add(vIndices[i + 1])
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip malformed lines
                }
            }
        }

        return Mesh(vertices.toFloatArray(), indices.toShortArray(), minX, maxX, minY, maxY, minZ, maxZ)
    }
}
