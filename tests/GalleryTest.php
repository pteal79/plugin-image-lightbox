<?php

use Pteal79\ImageLightbox\Gallery;
use Pteal79\ImageLightbox\ImageLightbox;

if (! function_exists('nativephp_call')) {
    /** Records bridge traffic so the tests can assert on it. */
    function nativephp_call(string $method, string $params = '{}'): ?string
    {
        $GLOBALS['imageLightboxCalls'][] = ['method' => $method, 'params' => json_decode($params, true)];

        return null;
    }
}

beforeEach(function () {
    $GLOBALS['imageLightboxCalls'] = [];

    $this->data = [
        ['index' => 2, 'uuid' => 'c', 'file' => 'https://example.com/c.jpg', 'text' => 'Third'],
        ['index' => 0, 'uuid' => 'a', 'file' => '/var/mobile/a.jpg'],
        ['index' => 1, 'uuid' => 'b', 'file' => 'php://storage/b.png', 'text' => 'Second'],
    ];
});

function lightboxCalls(): array
{
    return $GLOBALS['imageLightboxCalls'] ?? [];
}

function lastLightboxCall(): ?array
{
    $calls = lightboxCalls();

    return $calls === [] ? null : end($calls);
}

describe('Dataset normalisation', function () {

    it('sorts rows by their index', function () {
        $items = (new Gallery($this->data))->items();

        expect(array_column($items, 'uuid'))->toBe(['a', 'b', 'c']);
    });

    it('falls back to array position when a row has no index', function () {
        $items = (new Gallery([
            ['uuid' => 'a', 'file' => '/a.jpg'],
            ['uuid' => 'b', 'file' => '/b.jpg'],
        ]))->items();

        expect(array_column($items, 'index'))->toBe([0, 1]);
    });

    it('routes a file with a scheme to url and everything else to local', function () {
        $items = (new Gallery($this->data))->items();

        expect($items[0]['local'])->toBe('/var/mobile/a.jpg')
            ->and($items[0]['url'])->toBeNull()
            ->and($items[1]['url'])->toBe('php://storage/b.png')
            ->and($items[2]['url'])->toBe('https://example.com/c.jpg');
    });

    it('strips the file:// scheme to an absolute path', function () {
        $items = (new Gallery([['file' => 'file:///var/mobile/a.jpg']]))->items();

        expect($items[0]['local'])->toBe('/var/mobile/a.jpg')
            ->and($items[0]['url'])->toBeNull();
    });

    it('drops rows without a file', function () {
        $gallery = new Gallery([
            ['uuid' => 'a', 'file' => '/a.jpg'],
            ['uuid' => 'b', 'file' => '   '],
            ['uuid' => 'c'],
        ]);

        expect($gallery->count())->toBe(1);
    });

    it('normalises blank uuid and text to null', function () {
        $items = (new Gallery([['file' => '/a.jpg', 'uuid' => '', 'text' => '  ']]))->items();

        expect($items[0]['uuid'])->toBeNull()->and($items[0]['text'])->toBeNull();
    });

    it('accepts rows given as objects', function () {
        $items = (new Gallery([(object) ['index' => 0, 'uuid' => 'a', 'file' => '/a.jpg']]))->items();

        expect($items[0]['uuid'])->toBe('a');
    });
});

describe('Opening', function () {

    it('opens on the row carrying the given index value', function () {
        (new Gallery($this->data))->open(2);

        expect(lastLightboxCall()['method'])->toBe('ImageLightbox.Gallery')
            ->and(lastLightboxCall()['params']['startIndex'])->toBe(2);
    });

    it('treats an unmatched index as a position', function () {
        (new Gallery([
            ['index' => 10, 'uuid' => 'a', 'file' => '/a.jpg'],
            ['index' => 20, 'uuid' => 'b', 'file' => '/b.jpg'],
        ]))->open(1);

        expect(lastLightboxCall()['params']['startIndex'])->toBe(1);
    });

    it('clamps an out-of-range position', function () {
        (new Gallery($this->data))->open(99);

        expect(lastLightboxCall()['params']['startIndex'])->toBe(2);
    });

    it('starts on the first row and ends on the last', function () {
        $gallery = new Gallery($this->data);

        $gallery->start();
        expect(lastLightboxCall()['params']['startIndex'])->toBe(0);

        $gallery->last();
        expect(lastLightboxCall()['params']['startIndex'])->toBe(2);
    });

    it('sends the full dataset with every open', function () {
        (new Gallery($this->data))->start();

        expect(lastLightboxCall()['params']['items'])->toHaveCount(3);
    });

    it('does not call the bridge for an empty dataset', function () {
        (new Gallery([]))->start()->last()->open(0);

        expect(lightboxCalls())->toBeEmpty();
    });
});

describe('Configuration', function () {

    it('defaults every option to off', function () {
        expect((new Gallery)->settings())->toBe([
            'edit' => false, 'markup' => false, 'share' => false, 'delete' => false, 'title' => false,
        ]);
    });

    it('casts config values and ignores unknown keys', function () {
        $gallery = (new Gallery)->config(['edit' => 1, 'share' => 'yes', 'bogus' => true]);

        expect($gallery->settings())->toBe([
            'edit' => true, 'markup' => false, 'share' => true, 'delete' => false, 'title' => false,
        ]);
    });

    it('sends the title flag so native knows to show captions', function () {
        (new Gallery($this->data))->config(['title' => true])->start();

        expect(lastLightboxCall()['params']['title'])->toBeTrue();
    });

    it('sends the configuration alongside the dataset', function () {
        (new Gallery($this->data))->config(['share' => true, 'delete' => true])->start();

        expect(lastLightboxCall()['params'])->toMatchArray([
            'edit' => false, 'markup' => false, 'share' => true, 'delete' => true, 'title' => false,
        ]);
    });
});

describe('Updating', function () {

    it('pushes the replacement dataset to the bridge', function () {
        (new Gallery($this->data))->update([['index' => 0, 'uuid' => 'a', 'file' => '/a.jpg']]);

        expect(lastLightboxCall()['method'])->toBe('ImageLightbox.UpdateGallery')
            ->and(lastLightboxCall()['params']['items'])->toHaveCount(1);
    });

    it('pushes an empty dataset so native can dismiss', function () {
        (new Gallery($this->data))->update([]);

        expect(lastLightboxCall()['method'])->toBe('ImageLightbox.UpdateGallery')
            ->and(lastLightboxCall()['params']['items'])->toBe([]);
    });

    it('replaces the dataset without a bridge call via setItems', function () {
        $gallery = (new Gallery($this->data))->setItems([['file' => '/a.jpg']]);

        expect($gallery->count())->toBe(1)->and(lightboxCalls())->toBeEmpty();
    });

    it('closes the lightbox', function () {
        (new Gallery)->close();

        expect(lastLightboxCall()['method'])->toBe('ImageLightbox.Close');
    });
});

describe('Facade entry point', function () {

    it('returns the same gallery instance across calls', function () {
        $lightbox = new ImageLightbox;

        expect($lightbox->gallery($this->data))->toBe($lightbox->gallery());
    });

    it('reseeds the dataset when called with new rows', function () {
        $lightbox = new ImageLightbox;
        $lightbox->gallery($this->data);

        expect($lightbox->gallery([['file' => '/a.jpg']])->count())->toBe(1);
    });

    it('does not touch the dataset when called with no arguments', function () {
        $lightbox = new ImageLightbox;
        $lightbox->gallery($this->data);

        expect($lightbox->gallery()->count())->toBe(3);
    });

    it('is chainable from gallery through to open', function () {
        (new ImageLightbox)->gallery($this->data)->config(['share' => true])->last();

        expect(lastLightboxCall()['params'])
            ->toMatchArray(['share' => true, 'startIndex' => 2]);
    });
});
