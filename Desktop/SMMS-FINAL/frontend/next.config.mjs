/** @type {import('next').NextConfig} */
const nextConfig = {
  // Required for Docker standalone deployment
  output: 'standalone',

  typescript: {
    ignoreBuildErrors: false,
  },
  eslint: {
    ignoreDuringBuilds: false,
  },

  async rewrites() {
    // Only active in local development (npm run dev).
    // In Docker, Nginx handles /api/* → backend proxy.
    if (process.env.NODE_ENV === 'development') {
      return [
        {
          source: '/api/:path*',
          destination: 'http://localhost:9090/api/:path*',
        },
      ];
    }
    return [];
  },
};

export default nextConfig;
