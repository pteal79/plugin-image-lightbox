/**
 * ImageLightbox Plugin for NativePHP Mobile
 *
 * @example — remote URL
 * await ImageLightbox.show({
 *   url: 'https://example.com/photo.heic',
 *   imageId: '550e8400-e29b-41d4-a716-446655440000',
 *   edit: true, markup: true, share: true,
 * });
 *
 * @example — local file
 * await ImageLightbox.show({
 *   local: '/var/mobile/.../Documents/app/storage/app/public/photo.jpg',
 *   share: true,
 * });
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

// PascalCase export matches the PHP facade naming convention.
export const ImageLightbox = { show };

/** Fully-qualified event class names — use these instead of hardcoded strings. */
export const Events = {
    EditPressed:   'Nativephp\\ImageLightbox\\Events\\EditPressed',
    MarkupPressed: 'Nativephp\\ImageLightbox\\Events\\MarkupPressed',
    DeletePressed: 'Nativephp\\ImageLightbox\\Events\\DeletePressed',
    ClosePressed:  'Nativephp\\ImageLightbox\\Events\\ClosePressed',
};

export default ImageLightbox;
