/**
 * ImageLightbox Plugin for NativePHP Mobile
 *
 * @example — single image, remote URL
 * await ImageLightbox.show({
 *   url: 'https://example.com/photo.heic',
 *   imageId: '550e8400-e29b-41d4-a716-446655440000',
 *   edit: true, markup: true, share: true,
 * });
 *
 * @example — swipeable gallery
 * const gallery = ImageLightbox.gallery(rows).config({ share: true, delete: true });
 * await gallery.start();
 */

const baseUrl = '/_native/api/call';

async function bridgeCall(method, params = {}) {
    const response = await fetch(baseUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="csrf-token"]')?.content ?? '',
        },
        body: JSON.stringify({ method, params }),
    });

    const result = await response.json();

    if (result.status === 'error') {
        throw new Error(result.message ?? 'Native call failed');
    }

    const nativeResponse = result.data;
    return nativeResponse?.data ?? nativeResponse;
}

/**
 * Display an image in a full-screen native lightbox.
 *
 * @param {object}  options
 * @param {string}  [options.url]     - Remote http/https URL (jpg, jpeg, png, heic)
 * @param {string}  [options.local]   - Absolute local file path
 * @param {string}  [options.imageId] - Optional identifier included in event payloads
 * @param {boolean} [options.edit]    - Show an Edit button (default: false)
 * @param {boolean} [options.markup]  - Show a Markup button (default: false)
 * @param {boolean} [options.share]   - Show a Share button (default: false)
 * @param {boolean} [options.delete]  - Show a Delete button (default: false)
 */
async function show(options = {}) {
    return bridgeCall('ImageLightbox.Show', {
        url:      options.url     ?? null,
        local:    options.local   ?? null,
        imageId:  options.imageId ?? null,
        edit:     options.edit    ?? false,
        markup:   options.markup  ?? false,
        share:    options.share   ?? false,
        delete:   options.delete  ?? false,
    });
}

/** A `file` carrying a scheme is remote; anything else is an on-device path. */
function resolveSource(file) {
    if (/^file:\/\//i.test(file)) {
        return { url: null, local: file.slice(7) };
    }

    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(file)) {
        return { url: file, local: null };
    }

    return { url: null, local: file };
}

/** Turn the gallery dataset into the shape the native layer expects. */
function normaliseItems(items = []) {
    return [...items]
        .map((row, position) => {
            const file = String(row?.file ?? '').trim();
            if (file === '') return null;

            const { url, local } = resolveSource(file);

            return {
                index: Number.isInteger(row.index) ? row.index : position,
                uuid:  row.uuid ? String(row.uuid) : null,
                url,
                local,
                text:  row.text ? String(row.text) : null,
            };
        })
        .filter(Boolean)
        .sort((a, b) => a.index - b.index);
}

/**
 * A fluent handle over a set of images shown in the native lightbox.
 *
 * Each row of the dataset is shaped `{ index, uuid, file, text }`, where `file`
 * is a remote URL or an absolute local path and `text` is the caption shown when
 * `config({ title: true })`.
 */
class Gallery {
    constructor(items = []) {
        this.items = normaliseItems(items);
        this.settings = { edit: false, markup: false, share: false, delete: false, title: false };
    }

    /**
     * Toggle the toolbar buttons — `edit`, `markup`, `share`, `delete` — and the
     * `title` caption, which shows each row's `text` at the foot of the screen
     * while the image sits at its default zoom.
     */
    config(config = {}) {
        for (const key of Object.keys(this.settings)) {
            if (key in config) this.settings[key] = Boolean(config[key]);
        }

        return this;
    }

    /** Replace the dataset without touching a lightbox that may already be open. */
    setItems(items = []) {
        this.items = normaliseItems(items);

        return this;
    }

    /**
     * Replace the dataset and push it straight into an open lightbox. The visible
     * image is preserved by `uuid` where possible; an empty dataset closes the
     * lightbox. A no-op natively when nothing is open.
     */
    async update(items = []) {
        this.items = normaliseItems(items);

        return bridgeCall('ImageLightbox.UpdateGallery', { items: this.items });
    }

    /**
     * Open the lightbox on the row carrying this `index` value, falling back to
     * treating it as a zero-based position when no row matches.
     */
    async open(index = 0) {
        const match = this.items.findIndex((item) => item.index === index);

        return this.openAt(match >= 0 ? match : index);
    }

    /** Open the lightbox on the first image. */
    async start() {
        return this.openAt(0);
    }

    /** Open the lightbox on the last image. */
    async last() {
        return this.openAt(this.items.length - 1);
    }

    /** Dismiss an open lightbox. */
    async close() {
        return bridgeCall('ImageLightbox.Close', {});
    }

    async openAt(position) {
        if (this.items.length === 0) return null;

        return bridgeCall('ImageLightbox.Gallery', {
            ...this.settings,
            items: this.items,
            startIndex: Math.min(Math.max(position, 0), this.items.length - 1),
        });
    }
}

/**
 * Build a gallery from a dataset of `{ index, uuid, file, text }` rows.
 *
 * @param {Array<object>} items
 * @returns {Gallery}
 */
function gallery(items = []) {
    return new Gallery(items);
}

// PascalCase export matches the PHP facade naming convention.
export const ImageLightbox = { show, gallery };

/** Fully-qualified event class names — use these instead of hardcoded strings. */
export const Events = {
    EditPressed:   'Pteal79\\ImageLightbox\\Events\\EditPressed',
    MarkupPressed: 'Pteal79\\ImageLightbox\\Events\\MarkupPressed',
    DeletePressed: 'Pteal79\\ImageLightbox\\Events\\DeletePressed',
    ClosePressed:  'Pteal79\\ImageLightbox\\Events\\ClosePressed',
};

export { Gallery };

export default ImageLightbox;
