<?php

namespace Pteal79\ImageLightbox\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static void show(array $options)
 *
 * @see \Pteal79\ImageLightbox\ImageLightbox
 */
class ImageLightbox extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Pteal79\ImageLightbox\ImageLightbox::class;
    }
}
