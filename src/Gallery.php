<?php

namespace Pteal79\ImageLightbox;

use Illuminate\Contracts\Support\Arrayable;
use JsonSerializable;

/**
 * A fluent handle over a set of images shown in the native lightbox.
 *
 * The dataset ("gallery array") is a list of rows shaped like:
 *
 *     ['index' => 0, 'uuid' => '…', 'file' => '…', 'text' => 'Caption']
 *
 * `file` may be a remote URL (`http://`, `https://`, `php://`) or an absolute
 * local path; the scheme decides which the native layer receives. `index`
 * orders the gallery and is what `open()` looks up; `uuid` is forwarded as the
 * `imageId` on every event; `text` is the caption shown when `config(['title' => true])`.
 */
class Gallery
{
    /**
     * @var list<array{index: int, uuid: string|null, url: string|null, local: string|null, text: string|null}>
     */
    protected array $items = [];

    /** @var array{edit: bool, markup: bool, share: bool, delete: bool, title: bool} */
    protected array $config = [
        'edit'   => false,
        'markup' => false,
        'share'  => false,
        'delete' => false,
        'title'  => false,
    ];

    public function __construct(iterable $items = [])
    {
        $this->items = $this->normalise($items);
    }

    /**
     * Replace the dataset without touching a lightbox that may already be open.
     */
    public function setItems(iterable $items): static
    {
        $this->items = $this->normalise($items);

        return $this;
    }

    /**
     * Replace the dataset and push it straight into an open lightbox.
     *
     * The visible image is preserved by `uuid` where possible; passing an empty
     * dataset dismisses the lightbox. A no-op natively when nothing is open.
     */
    public function update(iterable $items): static
    {
        $this->items = $this->normalise($items);

        $this->call('ImageLightbox.UpdateGallery', ['items' => $this->items]);

        return $this;
    }

    /**
     * Toggle the toolbar buttons — `edit`, `markup`, `share`, `delete` — and the
     * `title` caption, which shows each row's `text` at the foot of the screen
     * while the image sits at its default zoom.
     *
     * @param  array<string, bool>  $config
     */
    public function config(array $config): static
    {
        foreach (array_keys($this->config) as $key) {
            if (array_key_exists($key, $config)) {
                $this->config[$key] = (bool) $config[$key];
            }
        }

        return $this;
    }

    /**
     * Open the lightbox on the row carrying this `index` value, falling back to
     * treating it as a zero-based position when no row matches.
     */
    public function open(int $index = 0): static
    {
        return $this->openAt($this->position($index));
    }

    /** Open the lightbox on the first image. */
    public function start(): static
    {
        return $this->openAt(0);
    }

    /** Open the lightbox on the last image. */
    public function last(): static
    {
        return $this->openAt(count($this->items) - 1);
    }

    /** Dismiss an open lightbox. */
    public function close(): static
    {
        $this->call('ImageLightbox.Close', []);

        return $this;
    }

    /**
     * The normalised dataset as it is handed to the native layer.
     *
     * @return list<array{index: int, uuid: string|null, url: string|null, local: string|null, text: string|null}>
     */
    public function items(): array
    {
        return $this->items;
    }

    public function count(): int
    {
        return count($this->items);
    }

    public function isEmpty(): bool
    {
        return $this->items === [];
    }

    /** The current toolbar configuration. */
    public function settings(): array
    {
        return $this->config;
    }

    // -----------------------------------------------------------------------

    protected function openAt(int $position): static
    {
        if ($this->items === []) {
            return $this;
        }

        $this->call('ImageLightbox.Gallery', array_merge($this->config, [
            'items'      => $this->items,
            'startIndex' => max(0, min($position, count($this->items) - 1)),
        ]));

        return $this;
    }

    /** Resolve a dataset `index` value to its position within the sorted set. */
    protected function position(int $index): int
    {
        foreach ($this->items as $position => $item) {
            if ($item['index'] === $index) {
                return $position;
            }
        }

        return $index;
    }

    /**
     * @return list<array{index: int, uuid: string|null, url: string|null, local: string|null, text: string|null}>
     */
    protected function normalise(iterable $items): array
    {
        $normalised = [];
        $position   = 0;

        foreach ($items as $item) {
            $row  = $this->rowToArray($item);
            $file = isset($row['file']) ? trim((string) $row['file']) : '';

            if ($file === '') {
                continue;
            }

            $source = $this->source($file);
            $uuid   = isset($row['uuid']) ? trim((string) $row['uuid']) : '';
            $text   = isset($row['text']) ? trim((string) $row['text']) : '';

            $normalised[] = [
                'index' => isset($row['index']) ? (int) $row['index'] : $position,
                'uuid'  => $uuid !== '' ? $uuid : null,
                'url'   => $source['url'],
                'local' => $source['local'],
                'text'  => $text !== '' ? $text : null,
            ];

            $position++;
        }

        // PHP 8 sorts are stable, so rows sharing an index keep their original order.
        usort($normalised, fn (array $a, array $b) => $a['index'] <=> $b['index']);

        return $normalised;
    }

    /** @return array{url: string|null, local: string|null} */
    protected function source(string $file): array
    {
        if (stripos($file, 'file://') === 0) {
            return ['url' => null, 'local' => substr($file, 7)];
        }

        if (preg_match('#^[a-z][a-z0-9+.\-]*://#i', $file) === 1) {
            return ['url' => $file, 'local' => null];
        }

        return ['url' => null, 'local' => $file];
    }

    /** @return array<string, mixed> */
    protected function rowToArray(mixed $row): array
    {
        return match (true) {
            is_array($row)                  => $row,
            $row instanceof Arrayable       => $row->toArray(),
            $row instanceof JsonSerializable => (array) $row->jsonSerialize(),
            is_object($row)                 => get_object_vars($row),
            default                         => [],
        };
    }

    protected function call(string $method, array $payload): void
    {
        if (function_exists('nativephp_call')) {
            nativephp_call($method, $payload === [] ? '{}' : json_encode($payload));
        }
    }
}
