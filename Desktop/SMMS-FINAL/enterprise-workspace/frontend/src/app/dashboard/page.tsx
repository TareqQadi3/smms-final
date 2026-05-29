'use client';

import { useEffect, useState } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors } from '@/lib/i18n';
import { dashboardApi, workOrderApi } from '@/lib/api';
import { DashboardStats, WorkOrder } from '@/lib/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import {
  ClipboardList, CheckCircle, Clock, AlertTriangle,
  TrendingUp, Wrench, Package, XCircle
} from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell
} from 'recharts';
import { format } from 'date-fns';

const statCards = (stats: DashboardStats, isAr: boolean) => [
  { label: isAr ? 'جديد' : 'New', value: stats.new, icon: <ClipboardList size={22} />, color: 'text-blue-600', bg: 'bg-blue-50' },
  { label: isAr ? 'قيد المراجعة' : 'Under Review', value: stats.under_review, icon: <Clock size={22} />, color: 'text-yellow-600', bg: 'bg-yellow-50' },
  { label: isAr ? 'قيد التنفيذ' : 'In Progress', value: stats.in_progress, icon: <Wrench size={22} />, color: 'text-orange-600', bg: 'bg-orange-50' },
  { label: isAr ? 'انتظار موافقة' : 'Waiting Approval', value: stats.waiting_approval, icon: <AlertTriangle size={22} />, color: 'text-red-600', bg: 'bg-red-50' },
  { label: isAr ? 'انتظار مواد' : 'Waiting Materials', value: stats.waiting_materials, icon: <Package size={22} />, color: 'text-amber-600', bg: 'bg-amber-50' },
  { label: isAr ? 'مكتمل' : 'Completed', value: stats.completed, icon: <CheckCircle size={22} />, color: 'text-green-600', bg: 'bg-green-50' },
  { label: isAr ? 'مغلق' : 'Closed', value: stats.closed, icon: <TrendingUp size={22} />, color: 'text-gray-600', bg: 'bg-gray-50' },
  { label: isAr ? 'ملغي' : 'Cancelled', value: stats.cancelled, icon: <XCircle size={22} />, color: 'text-red-400', bg: 'bg-red-50' },
];

export default function DashboardPage() {
  const { lang } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [recentOrders, setRecentOrders] = useState<WorkOrder[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const load = async () => {
      try {
        const [statsRes, ordersRes] = await Promise.all([
          dashboardApi.getManagement(),
          workOrderApi.getAll({ page: 0, size: 5 }),
        ]);
        setStats(statsRes.data);
        setRecentOrders(ordersRes.data.content || []);
      } catch { /* silent */ }
      finally { setLoading(false); }
    };
    load();
  }, []);

  const chartData = stats ? [
    { name: isAr ? 'جديد' : 'New', value: stats.new, fill: '#3b82f6' },
    { name: isAr ? 'مراجعة' : 'Review', value: stats.under_review, fill: '#eab308' },
    { name: isAr ? 'تنفيذ' : 'Progress', value: stats.in_progress, fill: '#f97316' },
    { name: isAr ? 'مكتمل' : 'Done', value: stats.completed, fill: '#22c55e' },
    { name: isAr ? 'مغلق' : 'Closed', value: stats.closed, fill: '#6b7280' },
  ] : [];

  if (loading) {
    return (
      <DashboardLayout>
        <div className="flex items-center justify-center h-64">
          <div className="w-10 h-10 border-4 border-blue-600 border-t-transparent rounded-full animate-spin" />
        </div>
      </DashboardLayout>
    );
  }

  return (
    <DashboardLayout>
      <div className="max-w-7xl mx-auto space-y-6">
        {/* Header */}
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{tr.dashboard}</h1>
          <p className="text-sm text-gray-500 mt-1">
            {isAr ? 'نظرة عامة على جميع طلبات الصيانة' : 'Overview of all maintenance requests'}
          </p>
        </div>

        {/* Stats Grid */}
        {stats && (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            {statCards(stats, isAr).map((card) => (
              <Card key={card.label} className="border-0 shadow-sm hover:shadow-md transition-shadow">
                <CardContent className="p-4">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-xs text-gray-500 font-medium">{card.label}</p>
                      <p className="text-3xl font-bold text-gray-900 mt-1">{card.value}</p>
                    </div>
                    <div className={`w-11 h-11 ${card.bg} ${card.color} rounded-xl flex items-center justify-center`}>
                      {card.icon}
                    </div>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        )}

        {/* Chart + Recent */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Chart */}
          <Card className="lg:col-span-2 border-0 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base font-semibold">
                {isAr ? 'توزيع البلاغات' : 'Tickets Distribution'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <ResponsiveContainer width="100%" height={220}>
                <BarChart data={chartData} barSize={40}>
                  <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                  <YAxis tick={{ fontSize: 12 }} />
                  <Tooltip />
                  <Bar dataKey="value" radius={[6, 6, 0, 0]}>
                    {chartData.map((entry, i) => (
                      <Cell key={i} fill={entry.fill} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </CardContent>
          </Card>

          {/* Summary */}
          <Card className="border-0 shadow-sm">
            <CardHeader className="pb-2">
              <CardTitle className="text-base font-semibold">
                {isAr ? 'ملخص سريع' : 'Quick Summary'}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {stats && [
                { label: isAr ? 'إجمالي النشطة' : 'Total Active', value: stats.new + stats.under_review + stats.assigned + stats.in_progress, color: 'text-blue-600' },
                { label: isAr ? 'تحتاج تدخل' : 'Needs Attention', value: stats.waiting_approval + stats.waiting_materials, color: 'text-red-600' },
                { label: isAr ? 'مكتملة' : 'Completed', value: stats.completed + stats.closed, color: 'text-green-600' },
              ].map((item) => (
                <div key={item.label} className="flex justify-between items-center py-2 border-b last:border-0">
                  <span className="text-sm text-gray-600">{item.label}</span>
                  <span className={`text-xl font-bold ${item.color}`}>{item.value}</span>
                </div>
              ))}
            </CardContent>
          </Card>
        </div>

        {/* Recent Work Orders */}
        <Card className="border-0 shadow-sm">
          <CardHeader className="pb-2">
            <CardTitle className="text-base font-semibold">{tr.recentOrders}</CardTitle>
          </CardHeader>
          <CardContent>
            {recentOrders.length === 0 ? (
              <p className="text-center text-gray-400 py-8 text-sm">{tr.noData}</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-gray-500">
                      <th className="text-start py-2 font-medium">{tr.ticketNumber}</th>
                      <th className="text-start py-2 font-medium">{tr.title}</th>
                      <th className="text-start py-2 font-medium">{tr.status}</th>
                      <th className="text-start py-2 font-medium">{tr.priority}</th>
                      <th className="text-start py-2 font-medium">{tr.date}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {recentOrders.map((wo) => (
                      <tr key={wo.id} className="border-b last:border-0 hover:bg-gray-50">
                        <td className="py-2.5 font-mono text-xs text-blue-600">{wo.ticketNumber}</td>
                        <td className="py-2.5 max-w-[200px] truncate">{wo.title}</td>
                        <td className="py-2.5">
                          <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${statusColors[wo.status] || ''}`}>
                            {wo.status}
                          </span>
                        </td>
                        <td className="py-2.5">
                          <Badge variant="outline" className="text-xs capitalize">{wo.priority}</Badge>
                        </td>
                        <td className="py-2.5 text-gray-500 text-xs">
                          {wo.createdAt ? format(new Date(wo.createdAt), 'dd/MM/yyyy') : '-'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </DashboardLayout>
  );
}
