'use client';

import { useEffect, useState } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT } from '@/lib/i18n';
import { dashboardApi } from '@/lib/api';
import { DashboardStats } from '@/lib/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer,
  BarChart, Bar, XAxis, YAxis, CartesianGrid
} from 'recharts';

const COLORS = ['#3b82f6','#f59e0b','#8b5cf6','#f97316','#ef4444','#22c55e','#6b7280','#dc2626'];

export default function ReportsPage() {
  const { lang } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';
  const [stats, setStats] = useState<DashboardStats | null>(null);

  useEffect(() => {
    dashboardApi.getManagement().then(r => setStats(r.data)).catch(() => {});
  }, []);

  const pieData = stats ? [
    { name: isAr ? 'جديد' : 'New', value: stats.new },
    { name: isAr ? 'مراجعة' : 'Under Review', value: stats.under_review },
    { name: isAr ? 'مُعيَّن' : 'Assigned', value: stats.assigned },
    { name: isAr ? 'تنفيذ' : 'In Progress', value: stats.in_progress },
    { name: isAr ? 'انتظار مواد' : 'Wait Materials', value: stats.waiting_materials },
    { name: isAr ? 'انتظار موافقة' : 'Wait Approval', value: stats.waiting_approval },
    { name: isAr ? 'مكتمل' : 'Completed', value: stats.completed },
    { name: isAr ? 'مغلق' : 'Closed', value: stats.closed },
  ].filter(d => d.value > 0) : [];

  const barData = stats ? [
    { name: isAr ? 'مفتوحة' : 'Open', value: (stats.new || 0) + (stats.under_review || 0) + (stats.assigned || 0) + (stats.in_progress || 0) },
    { name: isAr ? 'انتظار' : 'Waiting', value: (stats.waiting_materials || 0) + (stats.waiting_approval || 0) },
    { name: isAr ? 'مكتملة' : 'Completed', value: (stats.completed || 0) + (stats.closed || 0) },
    { name: isAr ? 'ملغية' : 'Cancelled', value: stats.cancelled || 0 },
  ] : [];

  return (
    <DashboardLayout>
      <div className="max-w-6xl mx-auto space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{tr.reports}</h1>
          <p className="text-sm text-muted-foreground">{isAr ? 'تقارير وإحصائيات النظام' : 'System reports and statistics'}</p>
        </div>

        {!stats ? (
          <div className="flex items-center justify-center h-64">
            <div className="w-10 h-10 border-4 border-blue-600 border-t-transparent rounded-full animate-spin" />
          </div>
        ) : (
          <>
            {/* Summary Cards */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
              {[
                { label: isAr ? 'إجمالي البلاغات' : 'Total Tickets', value: Object.values(stats).reduce((a,b) => a + b, 0), color: 'text-blue-600' },
                { label: isAr ? 'نشطة' : 'Active', value: stats.new + stats.under_review + stats.assigned + stats.in_progress, color: 'text-orange-600' },
                { label: isAr ? 'مكتملة' : 'Completed', value: stats.completed + stats.closed, color: 'text-green-700 dark:text-green-500' },
                { label: isAr ? 'تحتاج إجراء' : 'Need Action', value: stats.waiting_approval + stats.waiting_materials, color: 'text-red-600' },
              ].map(s => (
                <Card key={s.label} className="border-0 shadow-sm">
                  <CardContent className="p-4 text-center">
                    <p className={`text-3xl font-bold ${s.color}`}>{s.value}</p>
                    <p className="text-xs text-muted-foreground mt-1">{s.label}</p>
                  </CardContent>
                </Card>
              ))}
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              {/* Pie Chart */}
              <Card className="border-0 shadow-sm">
                <CardHeader className="pb-2">
                  <CardTitle className="text-base">{isAr ? 'توزيع البلاغات حسب الحالة' : 'Tickets by Status'}</CardTitle>
                </CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={300}>
                    <PieChart>
                      <Pie data={pieData} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={100} label>
                        {pieData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                      </Pie>
                      <Tooltip />
                      <Legend />
                    </PieChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>

              {/* Bar Chart */}
              <Card className="border-0 shadow-sm">
                <CardHeader className="pb-2">
                  <CardTitle className="text-base">{isAr ? 'ملخص الأداء' : 'Performance Summary'}</CardTitle>
                </CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={300}>
                    <BarChart data={barData} barSize={50}>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} />
                      <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                      <YAxis tick={{ fontSize: 12 }} />
                      <Tooltip />
                      <Bar dataKey="value" fill="#3b82f6" radius={[6,6,0,0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
            </div>

            {/* Status Table */}
            <Card className="border-0 shadow-sm">
              <CardHeader className="pb-2">
                <CardTitle className="text-base">{isAr ? 'تفاصيل الحالات' : 'Status Details'}</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="bg-muted/50 border-b">
                      <tr>
                        <th className="text-start px-4 py-3 font-medium text-muted-foreground">{tr.status}</th>
                        <th className="text-start px-4 py-3 font-medium text-muted-foreground">{isAr ? 'العدد' : 'Count'}</th>
                        <th className="text-start px-4 py-3 font-medium text-muted-foreground">{isAr ? 'النسبة' : 'Percentage'}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {Object.entries(stats).map(([key, val]) => {
                        const total = Object.values(stats).reduce((a,b) => a+b, 0);
                        const pct = total > 0 ? ((val / total) * 100).toFixed(1) : '0';
                        return (
                          <tr key={key} className="border-b last:border-0">
                            <td className="px-4 py-3 capitalize">{key.replace(/_/g,' ')}</td>
                            <td className="px-4 py-3 font-bold">{val}</td>
                            <td className="px-4 py-3">
                              <div className="flex items-center gap-2">
                                <div className="flex-1 bg-gray-100 rounded-full h-2 max-w-[100px]">
                                  <div className="bg-blue-500 h-2 rounded-full" style={{ width: `${pct}%` }} />
                                </div>
                                <span className="text-muted-foreground text-xs">{pct}%</span>
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </CardContent>
            </Card>
          </>
        )}
      </div>
    </DashboardLayout>
  );
}
