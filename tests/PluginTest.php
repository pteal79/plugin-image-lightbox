<?php

beforeEach(function () {
    $this->pluginPath   = dirname(__DIR__);
    $this->manifestPath = $this->pluginPath.'/nativephp.json';
});

describe('Plugin Manifest', function () {
    it('has a valid nativephp.json file', function () {
        expect(file_exists($this->manifestPath))->toBeTrue();
        $manifest = json_decode(file_get_contents($this->manifestPath), true);
        expect(json_last_error())->toBe(JSON_ERROR_NONE);
    });

    it('has required top-level keys', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);
        expect($manifest)->toHaveKeys(['namespace', 'bridge_functions', 'events']);
    });

    it('declares the Show bridge function', function () {
        $manifest   = json_decode(file_get_contents($this->manifestPath), true);
        $names      = array_column($manifest['bridge_functions'], 'name');
        expect($names)->toContain('ImageLightbox.Show');
    });

    it('declares the gallery bridge functions', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);
        $names    = array_column($manifest['bridge_functions'], 'name');
        expect($names)->toContain('ImageLightbox.Gallery')
            ->toContain('ImageLightbox.UpdateGallery')
            ->toContain('ImageLightbox.Close');
    });

    it('declares the viewpager2 dependency the Android gallery needs', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);
        $deps     = $manifest['android']['dependencies']['implementation'] ?? [];
        expect(implode(' ', $deps))->toContain('androidx.viewpager2:viewpager2');
    });

    it('has android and ios targets on every bridge function', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);
        foreach ($manifest['bridge_functions'] as $fn) {
            expect(isset($fn['android']) || isset($fn['ios']))->toBeTrue();
        }
    });

    it('registers the EditPressed and MarkupPressed events', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);
        expect($manifest['events'])->toContain('Pteal79\\ImageLightbox\\Events\\EditPressed');
        expect($manifest['events'])->toContain('Pteal79\\ImageLightbox\\Events\\MarkupPressed');
    });
});

describe('Native Code', function () {
    it('has Android Kotlin file', function () {
        expect(file_exists($this->pluginPath.'/resources/android/ImageLightboxFunctions.kt'))->toBeTrue();
    });

    it('has iOS Swift file', function () {
        expect(file_exists($this->pluginPath.'/resources/ios/ImageLightboxFunctions.swift'))->toBeTrue();
    });

    it('Kotlin file contains the Show bridge function class', function () {
        $contents = file_get_contents($this->pluginPath.'/resources/android/ImageLightboxFunctions.kt');
        expect($contents)->toContain('class Show');
    });

    it('Swift file contains the Show bridge function class', function () {
        $contents = file_get_contents($this->pluginPath.'/resources/ios/ImageLightboxFunctions.swift');
        expect($contents)->toContain('class Show');
    });

    it('Kotlin file contains the gallery bridge function classes', function () {
        $contents = file_get_contents($this->pluginPath.'/resources/android/ImageLightboxFunctions.kt');
        expect($contents)->toContain('class Gallery')
            ->toContain('class UpdateGallery')
            ->toContain('class Close');
    });

    it('Swift file contains the gallery bridge function classes', function () {
        $contents = file_get_contents($this->pluginPath.'/resources/ios/ImageLightboxFunctions.swift');
        expect($contents)->toContain('class Gallery')
            ->toContain('class UpdateGallery')
            ->toContain('class Close');
    });

    // Native sources escape their backslashes, so match on the namespace root
    // rather than the fully-qualified string.
    it('native files dispatch events under the package namespace', function () {
        foreach (['android/ImageLightboxFunctions.kt', 'ios/ImageLightboxFunctions.swift'] as $file) {
            $contents = file_get_contents($this->pluginPath.'/resources/'.$file);
            expect($contents)->toContain('Pteal79')->not->toContain('Nativephp');
        }
    });

    it('native files honour the title caption flag', function () {
        foreach (['android/ImageLightboxFunctions.kt', 'ios/ImageLightboxFunctions.swift'] as $file) {
            $contents = file_get_contents($this->pluginPath.'/resources/'.$file);
            expect($contents)->toContain('title');
        }
    });

    it('native files implement swipe-down-to-dismiss', function () {
        $kotlin = file_get_contents($this->pluginPath.'/resources/android/ImageLightboxFunctions.kt');
        expect($kotlin)->toContain('onDismissDrag')->toContain('dismissThreshold');

        $swift = file_get_contents($this->pluginPath.'/resources/ios/ImageLightboxFunctions.swift');
        expect($swift)->toContain('handleDismissPan')->toContain('dismissThreshold');
    });

    it('Kotlin file dispatches EditPressed event', function () {
        $contents = file_get_contents($this->pluginPath.'/resources/android/ImageLightboxFunctions.kt');
        expect($contents)->toContain('EditPressed');
    });

    it('Swift file dispatches EditPressed event', function () {
        $contents = file_get_contents($this->pluginPath.'/resources/ios/ImageLightboxFunctions.swift');
        expect($contents)->toContain('EditPressed');
    });
});

describe('PHP Classes', function () {
    it('has the main ImageLightbox class', function () {
        expect(file_exists($this->pluginPath.'/src/ImageLightbox.php'))->toBeTrue();
    });

    it('has the service provider', function () {
        expect(file_exists($this->pluginPath.'/src/ImageLightboxServiceProvider.php'))->toBeTrue();
    });

    it('has the facade', function () {
        expect(file_exists($this->pluginPath.'/src/Facades/ImageLightbox.php'))->toBeTrue();
    });

    it('has the EditPressed event', function () {
        expect(file_exists($this->pluginPath.'/src/Events/EditPressed.php'))->toBeTrue();
    });

    it('has the MarkupPressed event', function () {
        expect(file_exists($this->pluginPath.'/src/Events/MarkupPressed.php'))->toBeTrue();
    });

    it('ImageLightbox class has a show method', function () {
        $contents = file_get_contents($this->pluginPath.'/src/ImageLightbox.php');
        expect($contents)->toContain('public function show(');
    });

    it('has the Gallery class', function () {
        expect(file_exists($this->pluginPath.'/src/Gallery.php'))->toBeTrue();
    });
});

describe('Composer Configuration', function () {
    it('has valid composer.json', function () {
        $path = $this->pluginPath.'/composer.json';
        expect(file_exists($path))->toBeTrue();
        $composer = json_decode(file_get_contents($path), true);
        expect(json_last_error())->toBe(JSON_ERROR_NONE);
        expect($composer['type'])->toBe('nativephp-plugin');
    });

    it('registers the service provider via extra.laravel.providers', function () {
        $composer   = json_decode(file_get_contents($this->pluginPath.'/composer.json'), true);
        $providers  = $composer['extra']['laravel']['providers'] ?? [];
        expect($providers)->toContain('Pteal79\\ImageLightbox\\ImageLightboxServiceProvider');
    });
});
