'use client';
import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuthStore } from '@/store/authStore';

export default function Home() {
  const router = useRouter();
  const { init, user } = useAuthStore();

  useEffect(() => {
    init();
  }, [init]);

  useEffect(() => {
    const role = user?.role;
    if (!user) {
      router.replace('/login');
    } else if (role === 'technician') {
      router.replace('/my-tasks');
    } else if (role === 'resident' || role === 'department') {
      router.replace('/new-request');
    } else {
      router.replace('/dashboard');
    }
  }, [user, router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="text-center">
        <div className="w-12 h-12 border-4 border-blue-600 border-t-transparent rounded-full animate-spin mx-auto mb-4" />
        <p className="text-gray-500 text-sm">جاري التحميل...</p>
      </div>
    </div>
  );
}
