<?php

namespace Nativephp\ImageLightbox\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static void show(array $options)
 *
 * @see \Nativephp\ImageLightbox\ImageLightbox
 */
class ImageLightbox extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Nativephp\ImageLightbox\ImageLightbox::class;
    }
}
