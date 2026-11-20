/**
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer.util;

public class Constants {

    public static boolean DEBUG_MODE = false;

    /** Between 0 and 1, the thumbnails quality (default 0.3). Increasing this value may cause performance decrease */
    public static float THUMBNAIL_RATIO = 0.3f;

    /**
     * The size of the rendered parts (default 256)
     * Tinier : a little bit slower to have the whole page rendered but more reactive.
     * Bigger : user will have to wait longer to have the first visual results
     */
    public static float PART_SIZE = 256;

    /** Part of document above and below screen that should be preloaded, in dp */
    public static int PRELOAD_OFFSET = 20;

    /**
     * 单个瓦片允许渲染的最大边长（像素）。
     *
     * 原实现里瓦片实际尺寸约等于"整页在当前缩放下的像素"（PART_SIZE 经
     * pageRelativePartSize 反算），极端缩放下会分配超大位图；这里给出硬上限。
     */
    public static int MAX_PART_RENDER = 2048;

    public static class Cache {

        /** The size of the cache (number of bitmaps kept) */
        public static int CACHE_SIZE = 120;

        public static int THUMBNAILS_CACHE_SIZE = 8;

        /**
         * 位图缓存的总字节预算；<=0 表示由 PDFView 依据堆大小自动推算。
         *
         * 原实现只按"数量"淘汰（120 片），单片尺寸随缩放放大时总量可达上百 MB，
         * 这是大文件滚动到后段 OOM 的主因。改为按字节淘汰后总量恒定可控。
         */
        public static long CACHE_BYTES_LIMIT = 0;
    }

    public static class Pinch {

        public static float MAXIMUM_ZOOM = 10;

        public static float MINIMUM_ZOOM = 1;

    }

}
