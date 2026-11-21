package com.listen.reader

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * يستخرج نص المنشور من شجرة عناصر الشاشة.
 * القاعدة: المنشور المطلوب هو الذي يمر به الخط الأفقي عند ارتفاع الزر العائم.
 */
object TextExtractor {

    private const val TAG = "ListenExtract"

    /** أقصر من هذا يُعتبر أزرارًا وتسميات (إعجاب، تعليق، مشاركة...). */
    private const val MIN_LEN = 12
    /** النص البعيد عن خط الزر لا يُقبل إلا إن كان بهذا الطول على الأقل (حتى لا تُقرأ التواريخ والعدّادات). */
    private const val NEAR_MIN_LEN = 40
    private const val MAX_NODES = 5000
    private const val MAX_DEPTH = 80

    /** سطر أقصر من هذا قد يكون عنوانًا (اسم الكاتب، التاريخ) لا نص المنشور. */
    private const val HEADER_MAX = 60

    /** أقصى مسافة (dp) بين سطر العنوان ونص المنشور الذي تحته. */
    private const val HEADER_GAP_DP = 48

    private val MORE_LABEL = Regex(
        """^(…|\.{3})?\s*(see more|show more|more|عرض المزيد|إظهار المزيد|المزيد)$""",
        RegexOption.IGNORE_CASE
    )

    /** [expand]: زر «عرض المزيد» داخل النص المختار إن كان المنشور مقصوصًا. */
    class Picked(val text: String, val rect: Rect, val expand: AccessibilityNodeInfo?)

    private class Cand(val text: String, val rect: Rect, val isDesc: Boolean, val node: AccessibilityNodeInfo)

    fun extract(
        root: AccessibilityNodeInfo,
        y: Int,
        screenHeight: Int,
        density: Float,
        articleMode: Boolean,
    ): Picked? {
        val all = ArrayList<Cand>()
        collect(root, all, intArrayOf(0), 0)

        val texts = all.filter { !it.isDesc && it.text.length >= MIN_LEN }
        val descs = all.filter { it.isDesc && it.text.length >= MIN_LEN }
        Log.d(TAG, "nodes=${all.size} texts=${texts.size} descs=${descs.size} y=$y")

        // 1) نص ظاهر يمر به خط الزر
        var pick = texts.filter { y in it.rect.top..it.rect.bottom }.maxByOrNull { it.text.length }

        // 2) حاوية لها وصف (كصف التغريدة أو منشور فيسبوك) يمر بها خط الزر
        if (pick == null) {
            val box = descs.filter { y in it.rect.top..it.rect.bottom }.minByOrNull { it.rect.height() }
            if (box != null) {
                pick = texts.filter { box.rect.contains(it.rect) }.maxByOrNull { it.text.length } ?: box
            }
        }

        // 3) أقرب نص إلى الزر
        if (pick == null) {
            val range = screenHeight / 3
            pick = texts
                .filter { it.text.length >= NEAR_MIN_LEN && distance(it.rect, y) <= range }
                .minByOrNull { distance(it.rect, y) }
        }

        // 4) أطول نص على الشاشة — بشرط أن يكون نصًا حقيقيًا لا تسمية زر
        if (pick == null) pick = texts.filter { it.text.length >= NEAR_MIN_LEN }.maxByOrNull { it.text.length }

        // المختار سطر عنوان قصير ونص المنشور تحته مباشرة: اقرأ المنشور لا العنوان
        val header = pick
        if (header != null && header.text.length < HEADER_MAX) {
            val gap = (HEADER_GAP_DP * density).toInt()
            val body = texts
                .filter {
                    it !== header && it.text.length >= 40 && it.text.length > header.text.length * 2 &&
                        it.rect.top >= header.rect.top && it.rect.top - header.rect.bottom <= gap
                }
                .minByOrNull { it.rect.top }
            if (body != null) pick = body
        }

        val chosen = pick ?: return null
        val expand = if (chosen.isDesc) null else all.firstOrNull {
            it !== chosen && it.text.length <= 16 && it.node.isClickable &&
                chosen.rect.contains(it.rect) && MORE_LABEL.matches(it.text)
        }?.node
        Log.d(TAG, "picked desc=${chosen.isDesc} len=${chosen.text.length} rect=${chosen.rect} expand=${expand != null}")

        if (!articleMode || chosen.isDesc) return Picked(chosen.text, chosen.rect, expand)

        // وضع المقال: أضف الفقرات الظاهرة التي تلي النص المختار
        val sb = StringBuilder(chosen.text)
        val area = Rect(chosen.rect)
        var lastText = chosen.text
        for (i in all.indexOf(chosen) + 1 until all.size) {
            val c = all[i]
            if (c.isDesc || c.text.length < 30 || c.rect.top < chosen.rect.top || c.text == lastText) continue
            sb.append('\n').append(c.text)
            area.union(c.rect)
            lastText = c.text
        }
        return Picked(sb.toString(), area, expand)
    }

    private val IMAGE_DESC = Regex("""^(image|photo|video|صورة|فيديو|may be an image)""", RegexOption.IGNORE_CASE)

    /**
     * حدود الصورة (أو الفيديو) التي يمر بها خط الزر، أو أقرب صورة إليه إن لم يمر بواحدة.
     * يعيد null إن لم توجد صورة كبيرة على الشاشة.
     */
    fun findImageRect(root: AccessibilityNodeInfo, y: Int, screenWidth: Int, screenHeight: Int): Rect? {
        val images = ArrayList<Rect>()
        collectImages(root, images, screenWidth, screenHeight, intArrayOf(0), 0)
        val atY = images.filter { y in it.top..it.bottom }.minByOrNull { it.width() * it.height() }
        return atY ?: images.minByOrNull { distance(it, y) }
    }

    private fun collectImages(
        node: AccessibilityNodeInfo?,
        out: MutableList<Rect>,
        screenWidth: Int,
        screenHeight: Int,
        count: IntArray,
        depth: Int,
    ) {
        if (node == null || depth > MAX_DEPTH || count[0] >= MAX_NODES) return
        count[0]++
        if (!node.isVisibleToUser) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val isImage = node.className?.toString().orEmpty().contains("Image") ||
            IMAGE_DESC.containsMatchIn(node.contentDescription?.toString().orEmpty())
        if (isImage && rect.width() >= screenWidth / 2 && rect.height() >= screenHeight / 8) out.add(rect)
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            collectImages(child, out, screenWidth, screenHeight, count, depth + 1)
        }
    }

    private fun distance(r: Rect, y: Int) = if (y in r.top..r.bottom) 0 else minOf(abs(r.top - y), abs(r.bottom - y))

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<Cand>, count: IntArray, depth: Int) {
        if (node == null || depth > MAX_DEPTH || count[0] >= MAX_NODES) return
        count[0]++
        if (!node.isVisibleToUser) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && !node.isEditable && !node.isPassword) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                out.add(Cand(text, rect, false, node))
            } else {
                val desc = node.contentDescription?.toString()?.trim()
                if (!desc.isNullOrEmpty()) out.add(Cand(desc, rect, true, node))
            }
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            collect(child, out, count, depth + 1)
        }
    }
}
