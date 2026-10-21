/// Where the marketplace API lives. Production: https://open.elmanassa.shop.
/// For a local backend: --dart-define=OPEN_BASE_URL=http://10.0.2.2:8080 --dart-define=OPEN_HOST=open.platform.localtest.me
const String kBaseUrl = String.fromEnvironment('OPEN_BASE_URL', defaultValue: 'https://open.elmanassa.shop');

/// Sent as X-Forwarded-Host when set, so a local backend that is reached by IP still sees the marketplace host.
const String kHostOverride = String.fromEnvironment('OPEN_HOST');

/// Local development only: pictures are served by each shop's own host; when set, they are fetched through this address (the website dev server)
/// with the shop's host in the Host header.
const String kImageProxy = String.fromEnvironment('OPEN_IMAGE_PROXY');

const String kRootSite = String.fromEnvironment('OPEN_ROOT_SITE', defaultValue: 'https://elmanassa.shop');
