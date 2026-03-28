import Foundation
import UIKit
import WebKit

// MARK: - Bridge Function

enum ImageLightboxFunctions {

    class Show: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let remoteURL  = parameters["url"]     as? String
            let localPath  = parameters["local"]   as? String
            let imageId    = parameters["imageId"] as? String
            let showEdit   = parameters["edit"]    as? Bool ?? false
            let showMarkup = parameters["markup"]  as? Bool ?? false
            let showShare  = parameters["share"]   as? Bool ?? false
            let showDelete = parameters["delete"]  as? Bool ?? false

            let hasRemote = remoteURL?.isEmpty == false
            let hasLocal  = localPath?.isEmpty == false

            guard hasRemote || hasLocal else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "Either 'url' or 'local' is required")
            }

            DispatchQueue.main.async {
                let vc = ImageLightboxViewController(
                    remoteURL: remoteURL,
                    localPath: localPath,
                    imageId: imageId,
                    showEdit: showEdit,
                    showMarkup: showMarkup,
                    showShare: showShare,
                    showDelete: showDelete
                )
                vc.modalPresentationStyle = .overFullScreen
                vc.modalTransitionStyle   = .crossDissolve

                guard let windowScene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .first(where: { $0.activationState == .foregroundActive }),
                      let rootVC = windowScene.windows
                        .first(where: { $0.isKeyWindow })?
                        .rootViewController else {
                    print("[ImageLightbox] Failed to get root view controller")
                    return
                }
                rootVC.present(vc, animated: true)
            }

            return BridgeResponse.success(data: ["presented": true])
        }
    }
}

// MARK: - View Controller

class ImageLightboxViewController: UIViewController {

    private let remoteURL: String?
    private let localPath: String?
    private let imageId: String?
    private let showEdit: Bool
    private let showMarkup: Bool
    private let showShare: Bool
    private let showDelete: Bool

    private var scrollView: UIScrollView!
    private var imageView: UIImageView!
    private var loadingIndicator: UIActivityIndicatorView!
    private var toolbar: UIToolbar!

    /// Cached on-disk file URL used for the Share sheet.
    private var cachedImageFileURL: URL?

    // MARK: Init

    init(
        remoteURL: String?,
        localPath: String?,
        imageId: String?,
        showEdit: Bool,
        showMarkup: Bool,
        showShare: Bool,
        showDelete: Bool
    ) {
        self.remoteURL  = remoteURL
        self.localPath  = localPath
        self.imageId    = imageId
        self.showEdit   = showEdit
        self.showMarkup = showMarkup
        self.showShare  = showShare
        self.showDelete = showDelete
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    // MARK: Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
        loadImage()
    }

    // MARK: UI Setup

    private func setupUI() {
        view.backgroundColor = UIColor.black.withAlphaComponent(0.96)

        scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.minimumZoomScale = 1.0
        scrollView.maximumZoomScale = 5.0
        scrollView.delegate = self
        scrollView.showsVerticalScrollIndicator   = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.backgroundColor = .clear
        view.addSubview(scrollView)

        imageView = UIImageView()
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.contentMode = .scaleAspectFit
        imageView.backgroundColor = .clear
        scrollView.addSubview(imageView)

        loadingIndicator = UIActivityIndicatorView(style: .large)
        loadingIndicator.color = .white
        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(loadingIndicator)

        toolbar = UIToolbar()
        toolbar.translatesAutoresizingMaskIntoConstraints = false
        toolbar.setBackgroundImage(UIImage(), forToolbarPosition: .any, barMetrics: .default)
        toolbar.setShadowImage(UIImage(), forToolbarPosition: .any)
        toolbar.isTranslucent = true
        toolbar.tintColor = .white
        view.addSubview(toolbar)

        buildToolbar()
        activateConstraints()

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)
    }

    private func makeIconButton(systemName: String, action: Selector) -> UIBarButtonItem {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: systemName), for: .normal)
        button.tintColor = .white
        button.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        button.layer.cornerRadius = 16
        button.clipsToBounds = true
        button.frame = CGRect(x: 0, y: 0, width: 36, height: 36)
        button.addTarget(self, action: action, for: .touchUpInside)
        return UIBarButtonItem(customView: button)
    }

    private func buildToolbar() {
        var items: [UIBarButtonItem] = []
        if showEdit {
            items.append(makeIconButton(systemName: "pencil", action: #selector(editTapped)))
        }
        if showMarkup {
            items.append(makeIconButton(systemName: "pencil.tip.crop.circle", action: #selector(markupTapped)))
        }
        if showShare {
            items.append(makeIconButton(systemName: "square.and.arrow.up", action: #selector(shareTapped)))
        }
        if showDelete {
            items.append(makeIconButton(systemName: "trash", action: #selector(deleteTapped)))
        }
        items.append(UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil))
        items.append(makeIconButton(systemName: "xmark", action: #selector(closeTapped)))
        toolbar.setItems(items, animated: false)
    }

    private func activateConstraints() {
        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            toolbar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            toolbar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            toolbar.topAnchor.constraint(equalTo: safe.topAnchor),

            scrollView.topAnchor.constraint(equalTo: toolbar.bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: safe.bottomAnchor),

            imageView.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            imageView.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor),

            loadingIndicator.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            loadingIndicator.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    // MARK: Image Loading

    private func loadImage() {
        loadingIndicator.startAnimating()

        if let remote = remoteURL, !remote.isEmpty {
            loadRemoteImage(urlString: remote)
        } else if let local = localPath, !local.isEmpty {
            loadLocalImage(path: local)
        }
    }

    /// Load a remote URL, injecting WebView session cookies for authenticated endpoints.
    private func loadRemoteImage(urlString: String) {
        let normalizedUrl = urlString.hasPrefix("php://") ? "http://" + urlString.dropFirst("php://".count) : urlString
        guard let url = URL(string: normalizedUrl) else {
            showError("Invalid image URL:\n\(urlString)")
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
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.loadingIndicator.stopAnimating()

                    if let error = error {
                        let nsError = error as NSError
                        self.showError("Network error (\(nsError.domain) \(nsError.code)):\n\(nsError.localizedDescription)")
                        return
                    }

                    if let http = response as? HTTPURLResponse {
                        if !(200...299).contains(http.statusCode) {
                            self.showError("HTTP \(http.statusCode)\n\(url.absoluteString)")
                            return
                        }
                    }

                    guard let data, !data.isEmpty, let image = UIImage(data: data) else {
                        print("[ImageLightbox] Decode failed — data: \(data?.count ?? 0) bytes")
                        self.showError("Unable to decode image data.\n\(url.absoluteString)")
                        return
                    }

                    let ext = url.pathExtension.lowercased()
                    let safeExt = ["jpg","jpeg","png","heic","webp"].contains(ext) ? ext : "jpg"
                    let tempURL = FileManager.default.temporaryDirectory
                        .appendingPathComponent(UUID().uuidString)
                        .appendingPathExtension(safeExt)
                    try? data.write(to: tempURL)
                    self.cachedImageFileURL = tempURL
                    self.imageView.image = image
                }
            }.resume()
        }
    }

    private func loadLocalImage(path: String) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let fileURL = URL(fileURLWithPath: path)
            do {
                let data  = try Data(contentsOf: fileURL)
                guard let image = UIImage(data: data) else { throw ImageLightboxError.decodeFailed }
                self.cachedImageFileURL = fileURL
                DispatchQueue.main.async {
                    self.loadingIndicator.stopAnimating()
                    self.imageView.image = image
                }
            } catch {
                DispatchQueue.main.async {
                    self.loadingIndicator.stopAnimating()
                    self.showError(error.localizedDescription)
                }
            }
        }
    }

    private func showError(_ message: String) {
        let alert = UIAlertController(
            title: "Image Not Found",
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.dismiss(animated: true)
        })
        present(alert, animated: true)
    }

    // MARK: Toolbar Actions

    @objc private func closeTapped() {
        let id = imageId ?? ""
        dismiss(animated: true) {
            LaravelBridge.shared.send?("Nativephp\\ImageLightbox\\Events\\ClosePressed", ["imageId": id])
        }
    }

    @objc private func editTapped() {
        let id = imageId ?? ""
        dismiss(animated: true) {
            LaravelBridge.shared.send?("Nativephp\\ImageLightbox\\Events\\EditPressed", ["imageId": id])
        }
    }

    @objc private func markupTapped() {
        let id = imageId ?? ""
        dismiss(animated: true) {
            LaravelBridge.shared.send?("Nativephp\\ImageLightbox\\Events\\MarkupPressed", ["imageId": id])
        }
    }

    @objc private func deleteTapped() {
        let id = imageId ?? ""
        dismiss(animated: true) {
            LaravelBridge.shared.send?("Nativephp\\ImageLightbox\\Events\\DeletePressed", ["imageId": id])
        }
    }

    @objc private func shareTapped() {
        if let fileURL = cachedImageFileURL {
            presentShareSheet(items: [fileURL])
        } else if let image = imageView.image {
            presentShareSheet(items: [image])
        } else if let urlString = remoteURL,
                  let url = URL(string: urlString.hasPrefix("php://") ? "http://" + urlString.dropFirst("php://".count) : urlString) {
            // Remote image not yet cached — download then share
            loadingIndicator.startAnimating()
            WebView.dataStore.httpCookieStore.getAllCookies { [weak self] cookies in
                guard let self else { return }
                var request = URLRequest(url: url, timeoutInterval: 30)
                let relevant = cookies.filter { cookie in
                    guard let host = url.host else { return false }
                    let domain = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
                    return host.hasSuffix(domain) || domain.hasSuffix(host)
                }
                HTTPCookie.requestHeaderFields(with: relevant)
                    .forEach { request.setValue($1, forHTTPHeaderField: $0) }
                URLSession.shared.dataTask(with: request) { [weak self] data, _, _ in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        self.loadingIndicator.stopAnimating()
                        if let data, let image = UIImage(data: data) {
                            self.presentShareSheet(items: [image])
                        }
                    }
                }.resume()
            }
        }
    }

    private func presentShareSheet(items: [Any]) {
        let activityVC = UIActivityViewController(activityItems: items, applicationActivities: nil)
        activityVC.popoverPresentationController?.sourceView = toolbar
        present(activityVC, animated: true)
    }

    // MARK: Gesture Handlers

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

// MARK: - UIScrollViewDelegate

extension ImageLightboxViewController: UIScrollViewDelegate {

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        let offsetX = max((scrollView.bounds.width  - scrollView.contentSize.width)  / 2, 0)
        let offsetY = max((scrollView.bounds.height - scrollView.contentSize.height) / 2, 0)
        imageView.center = CGPoint(
            x: scrollView.contentSize.width  / 2 + offsetX,
            y: scrollView.contentSize.height / 2 + offsetY
        )
    }
}

// MARK: - Error Types

private enum ImageLightboxError: LocalizedError {
    case decodeFailed
    var errorDescription: String? { "The image data could not be decoded." }
}
