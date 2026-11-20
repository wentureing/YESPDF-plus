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
package com.github.barteksc.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.RectF;

import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.model.PagePart;
import com.github.barteksc.pdfviewer.util.Constants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import static com.github.barteksc.pdfviewer.util.Constants.Cache.CACHE_SIZE;
import static com.github.barteksc.pdfviewer.util.Constants.Cache.THUMBNAILS_CACHE_SIZE;

/**
 * 位图瓦片缓存。
 *
 * 与上游的差异：淘汰策略从"只按数量"改为 **数量 + 字节预算** 双限。
 * 上游单片位图的实际尺寸随缩放级别变化（约等于整页像素），
 * 120 片的数量上限在大页面上意味着上百 MB，滚动到文档后段时极易 OOM。
 */
class CacheManager {

    private final PriorityQueue<PagePart> passiveCache;

    private final PriorityQueue<PagePart> activeCache;

    private final List<PagePart> thumbnails;

    private final Object passiveActiveLock = new Object();

    private final PagePartComparator orderComparator = new PagePartComparator();

    /** 当前 active + passive 的位图总字节数 */
    private long cachedBytes = 0;

    public CacheManager() {
        activeCache = new PriorityQueue<>(CACHE_SIZE, orderComparator);
        passiveCache = new PriorityQueue<>(CACHE_SIZE, orderComparator);
        thumbnails = new ArrayList<>();
        if (Constants.Cache.CACHE_BYTES_LIMIT <= 0) {
            // 兜底：保持旧的等效预算（120 片 × 256×256×4B ≈ 30MB）
            Constants.Cache.CACHE_BYTES_LIMIT = (long) CACHE_SIZE * 256L * 256L * 4L;
        }
    }

    public void cachePart(PagePart part) {
        synchronized (passiveActiveLock) {
            long incoming = sizeOf(part);
            makeAFreeSpace(incoming);
            activeCache.offer(part);
            cachedBytes += incoming;
        }
    }

    public void makeANewSet() {
        synchronized (passiveActiveLock) {
            passiveCache.addAll(activeCache);
            activeCache.clear();
        }
    }

    private void makeAFreeSpace(long incoming) {
        synchronized (passiveActiveLock) {
            long limit = Constants.Cache.CACHE_BYTES_LIMIT;

            // 1) 先按字节预算淘汰：passive 里的瓦片离当前视口最远，优先释放
            while (cachedBytes + incoming > limit && !passiveCache.isEmpty()) {
                PagePart part = passiveCache.poll();
                cachedBytes -= sizeOf(part);
                recycleQuietly(part);
            }
            while (cachedBytes + incoming > limit && !activeCache.isEmpty()) {
                PagePart part = activeCache.poll();
                cachedBytes -= sizeOf(part);
                recycleQuietly(part);
            }

            // 2) 再保留数量上限，避免大量小瓦片把队列撑爆
            while ((activeCache.size() + passiveCache.size()) >= CACHE_SIZE
                    && !passiveCache.isEmpty()) {
                PagePart part = passiveCache.poll();
                cachedBytes -= sizeOf(part);
                recycleQuietly(part);
            }
            while ((activeCache.size() + passiveCache.size()) >= CACHE_SIZE
                    && !activeCache.isEmpty()) {
                PagePart part = activeCache.poll();
                cachedBytes -= sizeOf(part);
                recycleQuietly(part);
            }
            if (cachedBytes < 0) {
                cachedBytes = 0;
            }
        }
    }

    public void cacheThumbnail(PagePart part) {
        synchronized (thumbnails) {
            // If cache too big, remove and recycle
            while (thumbnails.size() >= THUMBNAILS_CACHE_SIZE) {
                recycleQuietly(thumbnails.remove(0));
            }

            // Then add thumbnail
            addWithoutDuplicates(thumbnails, part);
        }

    }

    public boolean upPartIfContained(int page, RectF pageRelativeBounds, int toOrder) {
        PagePart fakePart = new PagePart(page, null, pageRelativeBounds, false, 0);

        PagePart found;
        synchronized (passiveActiveLock) {
            if ((found = find(passiveCache, fakePart)) != null) {
                passiveCache.remove(found);
                found.setCacheOrder(toOrder);
                activeCache.offer(found);
                return true;
            }

            return find(activeCache, fakePart) != null;
        }
    }

    /**
     * Return true if already contains the described PagePart
     */
    public boolean containsThumbnail(int page, RectF pageRelativeBounds) {
        PagePart fakePart = new PagePart(page, null, pageRelativeBounds, true, 0);
        synchronized (thumbnails) {
            for (PagePart part : thumbnails) {
                if (part.equals(fakePart)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Add part if it doesn't exist, recycle bitmap otherwise
     */
    private void addWithoutDuplicates(Collection<PagePart> collection, PagePart newPart) {
        for (PagePart part : collection) {
            if (part.equals(newPart)) {
                recycleQuietly(newPart);
                return;
            }
        }
        collection.add(newPart);
    }

    @Nullable
    private static PagePart find(PriorityQueue<PagePart> vector, PagePart fakePart) {
        for (PagePart part : vector) {
            if (part.equals(fakePart)) {
                return part;
            }
        }
        return null;
    }

    public List<PagePart> getPageParts() {
        synchronized (passiveActiveLock) {
            List<PagePart> parts = new ArrayList<>(passiveCache);
            parts.addAll(activeCache);
            return parts;
        }
    }

    public List<PagePart> getThumbnails() {
        synchronized (thumbnails) {
            return thumbnails;
        }
    }

    public void recycle() {
        synchronized (passiveActiveLock) {
            for (PagePart part : passiveCache) {
                recycleQuietly(part);
            }
            passiveCache.clear();
            for (PagePart part : activeCache) {
                recycleQuietly(part);
            }
            activeCache.clear();
            cachedBytes = 0;
        }
        synchronized (thumbnails) {
            for (PagePart part : thumbnails) {
                recycleQuietly(part);
            }
            thumbnails.clear();
        }
    }

    private static long sizeOf(PagePart part) {
        Bitmap bitmap = part.getRenderedBitmap();
        return bitmap == null ? 0L : bitmap.getByteCount();
    }

    private static void recycleQuietly(PagePart part) {
        Bitmap bitmap = part.getRenderedBitmap();
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    class PagePartComparator implements Comparator<PagePart> {
        @Override
        public int compare(PagePart part1, PagePart part2) {
            if (part1.getCacheOrder() == part2.getCacheOrder()) {
                return 0;
            }
            return part1.getCacheOrder() > part2.getCacheOrder() ? 1 : -1;
        }
    }

}
