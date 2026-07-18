package app.vellum.reader.reader.turn

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * AGSL page curl (API 33+): the leaving page wraps around a vertical cylinder
 * peeling from the turning edge. Regions, right to left at mid-turn: revealed
 * page (with cast shadow) → back-of-paper on the cylinder → front-of-paper
 * bending into the roll → the flat overturned tail lying on top → flat front.
 * Runs entirely on the GPU; the two page textures are captured once per turn.
 */
object PageCurlShader {

    private const val SOURCE = """
        uniform float2 resolution;
        uniform float progress;    // 0 = flat, 1 = fully turned
        uniform float radius;      // cylinder radius in px
        uniform float4 paper;      // theme page color, used to wash the paper back
        uniform shader front;      // the page being turned
        uniform shader under;      // the page being revealed

        const float PI = 3.14159265;

        half4 sampleFront(float2 p) { return front.eval(p); }
        half4 sampleUnder(float2 p) { return under.eval(p); }

        half4 backFace(float srcX, float y) {
            half4 ink = sampleFront(float2(srcX, y));
            half4 base = half4(paper.rgb, 1.0);
            return mix(base, ink, 0.10); // faint ghost of print through the paper
        }

        half4 main(float2 fragCoord) {
            float w = resolution.x;
            // Work in "forward" space: page peels from the right edge.
            float2 p = fragCoord;
            float x = p.x;
            float y = p.y;

            // Curl line sweeps from w (untouched) to -PI*radius (fully turned).
            float d = w - progress * (w + PI * radius);

            float wrapped = max(0.0, (w - d) - PI * radius); // flat overturned tail length
            float tailStart = d - wrapped;

            if (x < d) {
                float edge = (wrapped > 0.0) ? tailStart : d;
                half4 frontC = sampleFront(p);
                // A flat flap barely shadows its thin edge — hairline contact
                // only. Without a flap, the darkness is the fold's crevice,
                // where the paper lifts away into the roll.
                float dist = max(edge - x, 0.0);
                float sh;
                if (wrapped > 0.0) {
                    sh = 1.0 - 0.10 * (1.0 - smoothstep(0.0, 10.0, dist));
                } else {
                    sh = 1.0 - 0.18 * (1.0 - smoothstep(0.0, radius * 0.5, dist));
                }
                frontC = half4(frontC.rgb * sh, 1.0);
                if (wrapped > 0.0) {
                    // Overturned flap lying face-down on top: antialiased edge
                    // and a darkened rim so the free edge looks like cut paper.
                    float cover = smoothstep(tailStart, tailStart + 3.0, x);
                    if (cover > 0.0) {
                        float src = d + PI * radius + (d - x);
                        half4 flap = backFace(src, y);
                        float rim = 0.86 + 0.14 * smoothstep(0.0, 26.0, x - tailStart);
                        flap = half4(flap.rgb * rim, 1.0);
                        return mix(frontC, flap, cover);
                    }
                }
                return frontC;
            }

            if (x <= d + radius) {
                float t = clamp((x - d) / radius, 0.0, 1.0);
                float theta = asin(t);
                // Back of the paper rolled over the top of the cylinder.
                float srcBack = d + (PI - theta) * radius;
                if (srcBack <= w) {
                    // Paper coming over the top of the roll: catches the light,
                    // dimming toward its edge-on silhouette.
                    half4 c = backFace(srcBack, y);
                    float shade = 0.90 + 0.10 * cos(theta);
                    return half4(c.rgb * shade, 1.0);
                }
                // Front of the paper bending into the roll — the inside of the
                // fold: darkest in the crevice, opening up toward the light.
                float srcFront = d + theta * radius;
                if (srcFront <= w) {
                    half4 c = sampleFront(float2(srcFront, y));
                    float shade = 0.62 + 0.26 * sin(theta);
                    return half4(c.rgb * shade, 1.0);
                }
            }

            // Revealed page beneath the overhanging roll — deepest shadow of
            // the whole turn, hugging the roll's silhouette.
            half4 c = sampleUnder(p);
            float dist = max(x - (d + radius), 0.0);
            float sh = 1.0 - 0.32 * (1.0 - smoothstep(0.0, radius * 1.4, dist)) * (1.0 - progress * 0.55);
            return half4(c.rgb * sh, 1.0);
        }
    """

    /** Builds a configured RuntimeShader for one frame of the curl. */
    fun create(
        width: Float,
        height: Float,
        progress: Float,
        radiusPx: Float,
        paperColor: Color,
        frontPage: ImageBitmap,
        underPage: ImageBitmap,
    ): RuntimeShader {
        val shader = RuntimeShader(SOURCE)
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("progress", progress)
        // Backward turns are the forward curl played in REVERSE (progress 1→0),
        // never mirrored: the returning page unrolls from the left and its fold
        // sweeps rightward, matching a real book. Mirroring here would make the
        // page enter from the right — exactly where the reader's finger isn't.
        shader.setFloatUniform("radius", radiusPx)
        shader.setFloatUniform("paper", paperColor.red, paperColor.green, paperColor.blue, 1f)
        shader.setInputShader(
            "front",
            BitmapShader(frontPage.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        shader.setInputShader(
            "under",
            BitmapShader(underPage.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        return shader
    }
}
