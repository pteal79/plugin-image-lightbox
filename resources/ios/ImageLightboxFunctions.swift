import Foundation
import UIKit
import WebKit

// MARK: - Models

/// One row of the gallery dataset.
struct ImageLightboxItem {
    let index: Int
    let uuid: String?
    let remoteURL: String?
    let localPath: String?
    let text: String?

    /// Stable cache key for the underlying image source.
    var sourceKey: String { remoteURL ?? localPath ?? "index:\(index)" }

    static func parse(_ raw: Any?) -> ImageLightboxItem? {
        guard let dict = raw as? [String: Any] else { return nil }

        let remote = nonEmpty(dict["url"])
        let local  = nonEmpty(dict["local"])

        guard remote != nil || local != nil else { return nil }

        return ImageLightboxItem(
            index: (dict["index"] as? NSNumber)?.intValue ?? 0,
            uuid: nonEmpty(dict["uuid"]),
            remoteURL: remote,
            localPath: local,
            text: nonEmpty(dict["text"])
        )
    }

    static func parseAll(_ raw: Any?) -> [ImageLightboxItem] {
        (raw as? [Any])?.compactMap { parse($0) } ?? []
    }

    private static func nonEmpty(_ value: Any?) -> String? {
        guard let string = value as? String, !string.isEmpty else { return nil }
        return string
    }
}

/// Which toolbar buttons the lightbox shows, and whether captions are on.
struct ImageLightboxConfig {
    let edit: Bool
    let markup: Bool
    let share: Bool
    let delete: Bool
    /// Show each row's `text` at the foot of the screen while at default zoom.
    let title: Bool

    init(_ parameters: [String: Any]) {
        edit   = parameters["edit"]   as? Bool ?? false
        markup = parameters["markup"] as? Bool ?? false
        share  = parameters["share"]  as? Bool ?? false
        delete = parameters["delete"] as? Bool ?? false
        title  = parameters["title"]  as? Bool ?? false
    }
}

// MARK: - Bridge Functions

enum ImageLightboxFunctions {

    /// Single image — presented as a one-item gallery so both paths share the UI.
    class Show: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let remoteURL = (parameters["url"]   as? String).flatMap { $0.isEmpty ? nil : $0 }
            let localPath = (parameters["local"] as? String).flatMap { $0.isEmpty ? nil : $0 }

            guard remoteURL != nil || localPath != nil else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "Either 'url' or 'local' is required")
            }

            let item = ImageLightboxItem(
                index: 0,
                uuid: parameters["imageId"] as? String,
                remoteURL: remoteURL,
                localPath: localPath,
                text: nil
            )

            ImageLightboxPresenter.present(items: [item], startIndex: 0, config: ImageLightboxConfig(parameters))

            return BridgeResponse.success(data: ["presented": true])
        }
    }

    /// Swipeable gallery.
    class Gallery: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let items = ImageLightboxItem.parseAll(parameters["items"])

            guard !items.isEmpty else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "'items' must contain at least one image")
            }

            let startIndex = min(max((parameters["startIndex"] as? NSNumber)?.intValue ?? 0, 0), items.count - 1)

            ImageLightboxPresenter.present(items: items, startIndex: startIndex, config: ImageLightboxConfig(parameters))

            return BridgeResponse.success(data: ["presented": true, "count": items.count])
        }
    }

    /// Swap the dataset of an already-open gallery. A no-op when nothing is open.
    class UpdateGallery: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let items = ImageLightboxItem.parseAll(parameters["items"])

            DispatchQueue.main.async {
                ImageLightboxGalleryViewController.current?.apply(items: items)
            }

            return BridgeResponse.success(data: ["updated": true, "count": items.count])
        }
    }

    /// Dismiss an open lightbox. A no-op when nothing is open.
    class Close: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            DispatchQueue.main.async {
                ImageLightboxGalleryViewController.current?.dismissLightbox()
            }

            return BridgeResponse.success(data: ["closed": true])
        }
    }
}

// MARK: - Presenter

enum ImageLightboxPresenter {

    static func present(items: [ImageLightboxItem], startIndex: Int, config: ImageLightboxConfig) {
        DispatchQueue.main.async {
            let vc = ImageLightboxGalleryViewController(items: items, startIndex: startIndex, config: config)
            vc.modalPresentationStyle = .overFullScreen
            vc.modalTransitionStyle   = .crossDissolve

            // Reopening while one is already up: tear the old one down first and
            // present from its host, since it is itself the top-most controller.
            if let existing = ImageLightboxGalleryViewController.current {
                ImageLightboxGalleryViewController.current = nil
                let host = existing.presentingViewController
                existing.dismiss(animated: false) {
                    (host ?? topViewController())?.present(vc, animated: true)
                }
                return
            }

            guard let host = topViewController() else {
                print("[ImageLightbox] Failed to get root view controller")
                return
            }

            host.present(vc, animated: true)
        }
    }

    /// The top-most presented controller, so the lightbox lands above any open modal.
    static func topViewController() -> UIViewController? {
        guard let windowScene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }),
              var top = windowScene.windows
                .first(where: { $0.isKeyWindow })?
                .rootViewController else {
            return nil
        }

        while let presented = top.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }

        return top
    }
}

// MARK: - Image Loading

/// Decoded-image and on-disk caches shared by every page of one lightbox, so
/// swiping back to an image never re-downloads it.
final class ImageLightboxLoader {

    private let cache = NSCache<NSString, UIImage>()
    private var fileURLs: [String: URL] = [:]
    private let lock = NSLock()

    init() {
        cache.countLimit = 12
    }

    func cachedImage(for item: ImageLightboxItem) -> UIImage? {
        cache.object(forKey: item.sourceKey as NSString)
    }

    func cachedFileURL(for item: ImageLightboxItem) -> URL? {
        lock.lock(); defer { lock.unlock() }
        return fileURLs[item.sourceKey]
    }

    func load(_ item: ImageLightboxItem, completion: @escaping (Result<UIImage, ImageLightboxError>) -> Void) {
        if let cached = cachedImage(for: item) {
            completion(.success(cached))
            return
        }

        if let remote = item.remoteURL {
            loadRemote(item, urlString: remote, completion: completion)
        } else if let local = item.localPath {
            loadLocal(item, path: local, completion: completion)
        } else {
            completion(.failure(.invalidSource("No image source was provided.")))
        }
    }

    /// Load a remote URL, injecting WebView session cookies for authenticated endpoints.
    private func loadRemote(
        _ item: ImageLightboxItem,
        urlString: String,
        completion: @escaping (Result<UIImage, ImageLightboxError>) -> Void
    ) {
        guard let url = URL(string: urlString) else {
            completion(.failure(.invalidSource("Invalid image URL:\n\(urlString)")))
            return
        }

        WebView.dataStore.httpCookieStore.getAllCookies { [weak self] cookies in
            guard let self else { return }

            var request = URLRequest(url: url, timeoutInterval: 30)
            request.setValue("image/*, */*", forHTTPHeaderField: "Accept")

            let relevant = cookies.filter { cookie in
                guard let host = url.host else { return false }
                let domain = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
                return host.hasSuffix(domain) || domain.hasSuffix(host)
            }
            HTTPCookie.requestHeaderFields(with: relevant)
                .forEach { request.setValue($1, forHTTPHeaderField: $0) }

            URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
                guard let self else { return }

                let outcome: Result<UIImage, ImageLightboxError>

                if let error = error {
                    let nsError = error as NSError
                    outcome = .failure(.network("Network error (\(nsError.domain) \(nsError.code)):\n\(nsError.localizedDescription)"))
                } else if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                    outcome = .failure(.network("HTTP \(http.statusCode)\n\(url.absoluteString)"))
                } else if let data, !data.isEmpty, let image = UIImage(data: data) {
                    let ext = url.pathExtension.lowercased()
                    let safeExt = ["jpg", "jpeg", "png", "heic", "webp"].contains(ext) ? ext : "jpg"
                    let tempURL = FileManager.default.temporaryDirectory
                        .appendingPathComponent(UUID().uuidString)
                        .appendingPathExtension(safeExt)
                    try? data.write(to: tempURL)
                    self.store(image: image, fileURL: tempURL, for: item)
                    outcome = .success(image)
                } else {
                    outcome = .failure(.decodeFailed("Unable to decode image data.\n\(url.absoluteString)"))
                }

                DispatchQueue.main.async { completion(outcome) }
            }.resume()
        }
    }

    private func loadLocal(
        _ item: ImageLightboxItem,
        path: String,
        completion: @escaping (Result<UIImage, ImageLightboxError>) -> Void
    ) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }

            let fileURL = URL(fileURLWithPath: path)
            let outcome: Result<UIImage, ImageLightboxError>

            if let data = try? Data(contentsOf: fileURL), let image = UIImage(data: data) {
                self.store(image: image, fileURL: fileURL, for: item)
                outcome = .success(image)
            } else {
                outcome = .failure(.decodeFailed("Unable to read image.\n\(path)"))
            }

            DispatchQueue.main.async { completion(outcome) }
        }
    }

    private func store(image: UIImage, fileURL: URL, for item: ImageLightboxItem) {
        cache.setObject(image, forKey: item.sourceKey as NSString)
        lock.lock()
        fileURLs[item.sourceKey] = fileURL
        lock.unlock()
    }
}

enum ImageLightboxError: LocalizedError {
    case invalidSource(String)
    case network(String)
    case decodeFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidSource(let message), .network(let message), .decodeFailed(let message):
            return message
        }
    }
}

// MARK: - Gallery View Controller

class ImageLightboxGalleryViewController: UIViewController {

    /// The presented instance, so `UpdateGallery` / `Close` can reach it.
    static weak var current: ImageLightboxGalleryViewController?

    private var items: [ImageLightboxItem]
    private let config: ImageLightboxConfig
    private var currentIndex: Int

    /// Shrinking the dataset makes UIKit clamp the offset mid-reload, which would
    /// otherwise overwrite the index we just resolved.
    private var isApplyingUpdate = false

    /// The caption yields the screen to the image as soon as the reader zooms in.
    private var isZoomedIn = false

    private var dismissPan: UIPanGestureRecognizer!

    /// Opacity of the backdrop at rest — the swipe fades it towards the app behind.
    private let backdropAlpha: CGFloat = 0.96

    private let loader = ImageLightboxLoader()

    private var collectionView: UICollectionView!
    private var buttonBar: UIView!
    private var counterLabel: UILabel!
    private var captionContainer: UIView!
    private var captionLabel: UILabel!

    private var currentItem: ImageLightboxItem? {
        items.indices.contains(currentIndex) ? items[currentIndex] : nil
    }

    // MARK: Init

    init(items: [ImageLightboxItem], startIndex: Int, config: ImageLightboxConfig) {
        self.items        = items
        self.config       = config
        self.currentIndex = min(max(startIndex, 0), max(items.count - 1, 0))
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    // MARK: Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        Self.current = self
        setupUI()
        refreshChrome()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()

        // The flow layout's item size follows the collection view's bounds, which
        // are only final after layout — and after a rotation.
        if let layout = collectionView.collectionViewLayout as? UICollectionViewFlowLayout,
           layout.itemSize != collectionView.bounds.size,
           collectionView.bounds.size != .zero {
            layout.itemSize = collectionView.bounds.size
            layout.invalidateLayout()
            scrollToCurrentIndex(animated: false)
        }
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            self?.scrollToCurrentIndex(animated: false)
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if Self.current === self { Self.current = nil }
    }

    // MARK: UI Setup

    private func setupUI() {
        view.backgroundColor = UIColor.black.withAlphaComponent(backdropAlpha)

        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection         = .horizontal
        layout.minimumLineSpacing      = 0
        layout.minimumInteritemSpacing = 0
        layout.sectionInset            = .zero

        collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        collectionView.isPagingEnabled  = true
        collectionView.backgroundColor  = .clear
        collectionView.dataSource       = self
        collectionView.delegate         = self
        collectionView.showsHorizontalScrollIndicator = false
        collectionView.showsVerticalScrollIndicator   = false
        collectionView.contentInsetAdjustmentBehavior = .never
        // A single image has nothing to page to — keep the horizontal drag inert.
        collectionView.isScrollEnabled  = items.count > 1
        collectionView.register(ImageLightboxCell.self, forCellWithReuseIdentifier: ImageLightboxCell.reuseId)
        view.addSubview(collectionView)

        buttonBar = UIView()
        buttonBar.translatesAutoresizingMaskIntoConstraints = false
        buttonBar.backgroundColor = .clear
        view.addSubview(buttonBar)

        counterLabel = UILabel()
        counterLabel.translatesAutoresizingMaskIntoConstraints = false
        counterLabel.textColor     = .white
        counterLabel.font          = .systemFont(ofSize: 15, weight: .semibold)
        counterLabel.textAlignment = .center
        counterLabel.isHidden      = items.count < 2
        buttonBar.addSubview(counterLabel)

        captionContainer = UIView()
        captionContainer.translatesAutoresizingMaskIntoConstraints = false
        captionContainer.backgroundColor    = UIColor.black.withAlphaComponent(0.55)
        captionContainer.layer.cornerRadius = 12
        captionContainer.clipsToBounds      = true
        captionContainer.alpha              = 0
        view.addSubview(captionContainer)

        captionLabel = UILabel()
        captionLabel.translatesAutoresizingMaskIntoConstraints = false
        captionLabel.textColor     = .white
        captionLabel.font          = .systemFont(ofSize: 15)
        captionLabel.numberOfLines = 0
        captionLabel.textAlignment = .center
        captionContainer.addSubview(captionLabel)

        buildButtonBar()
        activateConstraints()

        dismissPan = UIPanGestureRecognizer(target: self, action: #selector(handleDismissPan(_:)))
        dismissPan.delegate = self
        view.addGestureRecognizer(dismissPan)
    }

    private func makeIconButton(systemName: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: systemName), for: .normal)
        button.tintColor = .white
        button.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        button.layer.cornerRadius = 16
        button.clipsToBounds = true
        button.translatesAutoresizingMaskIntoConstraints = false
        button.addTarget(self, action: action, for: .touchUpInside)
        NSLayoutConstraint.activate([
            button.widthAnchor.constraint(equalToConstant: 36),
            button.heightAnchor.constraint(equalToConstant: 36),
        ])
        return button
    }

    private func buildButtonBar() {
        var leadingButtons: [UIButton] = []
        if config.edit   { leadingButtons.append(makeIconButton(systemName: "pencil",                 action: #selector(editTapped)))   }
        if config.markup { leadingButtons.append(makeIconButton(systemName: "pencil.tip.crop.circle", action: #selector(markupTapped))) }
        if config.share  { leadingButtons.append(makeIconButton(systemName: "square.and.arrow.up",    action: #selector(shareTapped)))  }
        if config.delete { leadingButtons.append(makeIconButton(systemName: "trash",                  action: #selector(deleteTapped))) }

        let closeButton = makeIconButton(systemName: "xmark", action: #selector(closeTapped))

        (leadingButtons + [closeButton]).forEach { buttonBar.addSubview($0) }

        var constraints: [NSLayoutConstraint] = [
            closeButton.trailingAnchor.constraint(equalTo: buttonBar.trailingAnchor, constant: -16),
            closeButton.centerYAnchor.constraint(equalTo: buttonBar.centerYAnchor),
        ]

        var prev: UIButton? = nil
        for btn in leadingButtons {
            let leading: NSLayoutConstraint = prev == nil
                ? btn.leadingAnchor.constraint(equalTo: buttonBar.leadingAnchor, constant: 16)
                : btn.leadingAnchor.constraint(equalTo: prev!.trailingAnchor, constant: 8)
            constraints += [leading, btn.centerYAnchor.constraint(equalTo: buttonBar.centerYAnchor)]
            prev = btn
        }

        NSLayoutConstraint.activate(constraints)
    }

    private func activateConstraints() {
        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            buttonBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            buttonBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            buttonBar.topAnchor.constraint(equalTo: safe.topAnchor),
            buttonBar.heightAnchor.constraint(equalToConstant: 52),

            counterLabel.centerXAnchor.constraint(equalTo: buttonBar.centerXAnchor),
            counterLabel.centerYAnchor.constraint(equalTo: buttonBar.centerYAnchor),

            collectionView.topAnchor.constraint(equalTo: buttonBar.bottomAnchor),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.bottomAnchor.constraint(equalTo: safe.bottomAnchor),

            captionContainer.leadingAnchor.constraint(greaterThanOrEqualTo: safe.leadingAnchor, constant: 16),
            captionContainer.trailingAnchor.constraint(lessThanOrEqualTo: safe.trailingAnchor, constant: -16),
            captionContainer.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            captionContainer.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -16),

            captionLabel.topAnchor.constraint(equalTo: captionContainer.topAnchor, constant: 10),
            captionLabel.bottomAnchor.constraint(equalTo: captionContainer.bottomAnchor, constant: -10),
            captionLabel.leadingAnchor.constraint(equalTo: captionContainer.leadingAnchor, constant: 14),
            captionLabel.trailingAnchor.constraint(equalTo: captionContainer.trailingAnchor, constant: -14),
        ])
    }

    // MARK: Dataset

    /// Replace the dataset in place, holding the reader on the same image where
    /// it survived the update. An empty dataset dismisses the lightbox.
    func apply(items newItems: [ImageLightboxItem]) {
        guard !newItems.isEmpty else {
            dismissLightbox()
            return
        }

        let previousUUID = currentItem?.uuid
        let previousIndex = currentIndex

        items = newItems

        if let uuid = previousUUID, let match = items.firstIndex(where: { $0.uuid == uuid }) {
            currentIndex = match
        } else {
            currentIndex = min(previousIndex, items.count - 1)
        }

        isApplyingUpdate = true
        isZoomedIn = false
        collectionView.isScrollEnabled = items.count > 1
        collectionView.reloadData()
        collectionView.layoutIfNeeded()
        scrollToCurrentIndex(animated: false)
        isApplyingUpdate = false

        refreshChrome()
    }

    func dismissLightbox() {
        if Self.current === self { Self.current = nil }
        dismiss(animated: true)
    }

    private func scrollToCurrentIndex(animated: Bool) {
        let width = collectionView.bounds.width
        guard width > 0, items.indices.contains(currentIndex) else { return }
        collectionView.setContentOffset(CGPoint(x: CGFloat(currentIndex) * width, y: 0), animated: animated)
    }

    /// Sync the counter and caption with `currentIndex`.
    private func refreshChrome() {
        counterLabel.isHidden = items.count < 2
        counterLabel.text     = "\(currentIndex + 1) / \(items.count)"

        let caption = config.title ? currentItem?.text : nil

        captionLabel.text = caption
        captionContainer.isHidden = false
        UIView.animate(withDuration: 0.2) {
            self.captionContainer.alpha = (caption == nil || self.isZoomedIn) ? 0 : 1
        }
    }

    private func syncIndexToScrollPosition() {
        let width = collectionView.bounds.width
        guard width > 0, !isApplyingUpdate else { return }

        let page = Int((collectionView.contentOffset.x / width).rounded())
        let clamped = min(max(page, 0), max(items.count - 1, 0))

        guard clamped != currentIndex else { return }
        currentIndex = clamped
        // The incoming page is always at default zoom.
        isZoomedIn = false
        refreshChrome()
    }

    // MARK: Swipe To Dismiss

    /// How far the image must travel before releasing closes the lightbox.
    private var dismissThreshold: CGFloat {
        max(view.bounds.height * 0.20, 120)
    }

    @objc private func handleDismissPan(_ gesture: UIPanGestureRecognizer) {
        let translation = gesture.translation(in: view)

        switch gesture.state {
        case .began:
            // Hand the whole gesture to the dismiss: paging would fight it.
            collectionView.isScrollEnabled = false

        case .changed:
            let drop = max(translation.y, 0)
            let progress = min(drop / dismissThreshold, 1)
            let scale = 1 - progress * 0.2

            collectionView.transform = CGAffineTransform(scaleX: scale, y: scale)
                .concatenating(CGAffineTransform(translationX: translation.x, y: drop))

            view.backgroundColor = UIColor.black.withAlphaComponent(backdropAlpha * (1 - progress))
            buttonBar.alpha = 1 - progress

            let showsCaption = config.title && currentItem?.text != nil && !isZoomedIn
            captionContainer.alpha = showsCaption ? 1 - progress : 0

        case .ended, .cancelled, .failed:
            let velocity = gesture.velocity(in: view)

            if gesture.state == .ended, translation.y > dismissThreshold || velocity.y > 1200 {
                dismissBySwipe()
            } else {
                springBackFromSwipe()
            }

        default:
            break
        }
    }

    private func dismissBySwipe() {
        let payload = eventPayload()
        if Self.current === self { Self.current = nil }

        // Carry the image off the bottom while the backdrop fades behind it.
        UIView.animate(withDuration: 0.2, animations: {
            self.collectionView.transform = CGAffineTransform(translationX: 0, y: self.view.bounds.height)
            self.collectionView.alpha  = 0
            self.view.backgroundColor  = .clear
            self.buttonBar.alpha       = 0
            self.captionContainer.alpha = 0
        }, completion: { _ in
            self.dismiss(animated: false) {
                LaravelBridge.shared.send?("Pteal79\\ImageLightbox\\Events\\ClosePressed", payload)
            }
        })
    }

    private func springBackFromSwipe() {
        UIView.animate(
            withDuration: 0.3,
            delay: 0,
            usingSpringWithDamping: 0.85,
            initialSpringVelocity: 0,
            options: [.allowUserInteraction],
            animations: {
                self.collectionView.transform = .identity
                self.view.backgroundColor = UIColor.black.withAlphaComponent(self.backdropAlpha)
                self.buttonBar.alpha = 1
            },
            completion: { _ in
                self.collectionView.isScrollEnabled = self.items.count > 1
                self.refreshChrome()
            }
        )
    }

    // MARK: Toolbar Actions

    /// Every event carries the row's `uuid` as `imageId` plus its dataset `index`.
    private func eventPayload() -> [String: Any?] {
        ["imageId": currentItem?.uuid ?? "", "index": currentItem?.index ?? 0]
    }

    private func dispatch(_ event: String) {
        LaravelBridge.shared.send?("Pteal79\\ImageLightbox\\Events\\\(event)", eventPayload())
    }

    private func dismissAndDispatch(_ event: String) {
        let payload = eventPayload()
        if Self.current === self { Self.current = nil }
        dismiss(animated: true) {
            LaravelBridge.shared.send?("Pteal79\\ImageLightbox\\Events\\\(event)", payload)
        }
    }

    @objc private func closeTapped()  { dismissAndDispatch("ClosePressed") }
    @objc private func editTapped()   { dismissAndDispatch("EditPressed") }
    @objc private func markupTapped() { dismissAndDispatch("MarkupPressed") }

    @objc private func deleteTapped() {
        // In a gallery the reader stays put: the app removes the row and calls
        // `update()`, which slides the next image in (or closes on the last one).
        if items.count > 1 {
            dispatch("DeletePressed")
        } else {
            dismissAndDispatch("DeletePressed")
        }
    }

    @objc private func shareTapped() {
        guard let item = currentItem else { return }

        if let fileURL = loader.cachedFileURL(for: item) {
            presentShareSheet(items: [fileURL])
            return
        }

        if let image = loader.cachedImage(for: item) {
            presentShareSheet(items: [image])
            return
        }

        // Not loaded yet — fetch, then share.
        loader.load(item) { [weak self] result in
            guard let self, case .success(let image) = result else { return }
            self.presentShareSheet(items: [self.loader.cachedFileURL(for: item) ?? image])
        }
    }

    private func presentShareSheet(items: [Any]) {
        let activityVC = UIActivityViewController(activityItems: items, applicationActivities: nil)
        activityVC.popoverPresentationController?.sourceView = buttonBar
        present(activityVC, animated: true)
    }

    /// Single-image mode keeps the original blocking alert; a gallery shows the
    /// failure inline so one bad row doesn't tear down the whole session.
    fileprivate func reportLoadFailure(_ message: String) {
        guard items.count == 1, presentedViewController == nil else { return }

        let alert = UIAlertController(title: "Image Not Found", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.dismissLightbox()
        })
        present(alert, animated: true)
    }
}

// MARK: - Gesture Delegate

extension ImageLightboxGalleryViewController: UIGestureRecognizerDelegate {

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === dismissPan else { return true }

        // A zoomed image pans inside its own scroll view instead.
        guard !isZoomedIn else { return false }

        let velocity = dismissPan.velocity(in: view)

        return velocity.y > 0 && velocity.y > abs(velocity.x)
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
    ) -> Bool {
        gestureRecognizer === dismissPan
    }
}

// MARK: - Collection View

extension ImageLightboxGalleryViewController: UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        items.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: ImageLightboxCell.reuseId, for: indexPath)

        guard let imageCell = cell as? ImageLightboxCell, items.indices.contains(indexPath.item) else {
            return cell
        }

        imageCell.onLoadFailed = { [weak self] message in self?.reportLoadFailure(message) }
        imageCell.onZoomChanged = { [weak self] item, zoomedIn in
            guard let self, item == self.currentIndex, self.isZoomedIn != zoomedIn else { return }
            self.isZoomedIn = zoomedIn
            self.refreshChrome()
        }
        imageCell.configure(with: items[indexPath.item], loader: loader, at: indexPath.item)

        return imageCell
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        collectionView.bounds.size
    }

    func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        (cell as? ImageLightboxCell)?.resetZoom()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        syncIndexToScrollPosition()
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        syncIndexToScrollPosition()
    }
}

// MARK: - Cell

/// One page: a zoomable image with its own loading and failure state.
final class ImageLightboxCell: UICollectionViewCell {

    static let reuseId = "ImageLightboxCell"

    private let scrollView       = UIScrollView()
    private let imageView        = UIImageView()
    private let loadingIndicator = UIActivityIndicatorView(style: .large)
    private let messageLabel     = UILabel()

    /// Identifies the in-flight load so a recycled cell ignores a stale result.
    private var loadToken: String?

    /// This cell's position, so the controller can ignore reports from off-screen pages.
    private var itemIndex = 0

    var onLoadFailed: ((String) -> Void)?

    /// Reports `(index, isZoomedIn)` whenever this page leaves or returns to default zoom.
    var onZoomChanged: ((Int, Bool) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupUI()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    private func setupUI() {
        contentView.backgroundColor = .clear

        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.minimumZoomScale = 1.0
        scrollView.maximumZoomScale = 5.0
        scrollView.delegate = self
        scrollView.showsVerticalScrollIndicator   = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.backgroundColor = .clear
        // Without bouncing, a pan at zoom 1 (or at the edge of a zoomed image)
        // falls through to the paging collection view instead of rubber-banding.
        scrollView.bounces     = false
        scrollView.bouncesZoom = true
        contentView.addSubview(scrollView)

        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.contentMode    = .scaleAspectFit
        imageView.backgroundColor = .clear
        scrollView.addSubview(imageView)

        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        loadingIndicator.color = .white
        loadingIndicator.hidesWhenStopped = true
        contentView.addSubview(loadingIndicator)

        messageLabel.translatesAutoresizingMaskIntoConstraints = false
        messageLabel.textColor     = UIColor.white.withAlphaComponent(0.7)
        messageLabel.font          = .systemFont(ofSize: 15)
        messageLabel.textAlignment = .center
        messageLabel.numberOfLines = 0
        messageLabel.isHidden      = true
        contentView.addSubview(messageLabel)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: contentView.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),

            imageView.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            imageView.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor),

            loadingIndicator.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            loadingIndicator.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),

            messageLabel.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            messageLabel.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            messageLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 24),
            messageLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -24),
        ])

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        loadToken          = nil
        imageView.image    = nil
        messageLabel.isHidden = true
        onLoadFailed       = nil
        onZoomChanged      = nil
        resetZoom()
    }

    func resetZoom() {
        scrollView.setZoomScale(scrollView.minimumZoomScale, animated: false)
    }

    func configure(with item: ImageLightboxItem, loader: ImageLightboxLoader, at index: Int) {
        let token = item.sourceKey
        loadToken = token
        itemIndex = index
        messageLabel.isHidden = true

        if let cached = loader.cachedImage(for: item) {
            imageView.image = cached
            loadingIndicator.stopAnimating()
            return
        }

        imageView.image = nil
        loadingIndicator.startAnimating()

        loader.load(item) { [weak self] result in
            guard let self, self.loadToken == token else { return }

            self.loadingIndicator.stopAnimating()

            switch result {
            case .success(let image):
                self.imageView.image = image
            case .failure(let error):
                let message = error.errorDescription ?? "Unable to load image."
                self.messageLabel.text     = message
                self.messageLabel.isHidden = false
                self.onLoadFailed?(message)
            }
        }
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        if scrollView.zoomScale > scrollView.minimumZoomScale {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
        } else {
            let point = gesture.location(in: imageView)
            let size  = CGSize(width: scrollView.bounds.width / 2, height: scrollView.bounds.height / 2)
            scrollView.zoom(
                to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                           width: size.width, height: size.height),
                animated: true
            )
        }
    }
}

extension ImageLightboxCell: UIScrollViewDelegate {

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        let offsetX = max((scrollView.bounds.width  - scrollView.contentSize.width)  / 2, 0)
        let offsetY = max((scrollView.bounds.height - scrollView.contentSize.height) / 2, 0)
        imageView.center = CGPoint(
            x: scrollView.contentSize.width  / 2 + offsetX,
            y: scrollView.contentSize.height / 2 + offsetY
        )

        onZoomChanged?(itemIndex, scrollView.zoomScale > scrollView.minimumZoomScale * 1.01)
    }
}
