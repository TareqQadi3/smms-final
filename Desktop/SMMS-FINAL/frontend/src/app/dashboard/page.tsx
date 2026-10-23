'use client';

import { useEffect, useState } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors, priorityColors, tStatus, tPriority } from '@/lib/i18n';
import { dashboardApi, workOrderApi } from '@/lib/api';
import { DashboardStats, WorkOrder } from '@/lib/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  ClipboardList, CheckCircle, Clock, AlertTriangle,
  TrendingUp, Wrench, Package, XCircle
} from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell
} from 'recharts';
import { format } from 'date-fns';
import Link from 'next/link';

const MANAGEMENT_ROLES = ['system_admin','region_supervisor','hospital_supervisor','maintenance_manager','electrical_manager','operating_company_manager'];
const CONTROL_ROOM_ROLES = ['control_room'];
const TECHNICIAN_ROLES = ['technician'];

export default function DashboardPage() {
  const { lang, user } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [recentOrders, setRecentOrders] = useState<WorkOrder[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const role = user?.role || '';
    const load = async () => {
      try {
        let statsData = null;
        if (MANAGEMENT_ROLES.includes(role)) {
          const res = await dashboardApi.getManagement();
          statsData = res.data;
        } else if (CONTROL_ROOM_ROLES.includes(role)) {
          const res = await dashboardApi.getControlRoom();
          statsData = res.data;
        } else if (TECHNICIAN_ROLES.includes(role)) {
          const res = await dashboardApi.getTechnician();
          statsData = res.data;
        }
        setStats(statsData);
        const ordersRes = await workOrderApi.getAll({ page: 0, size: 5 });
        setRecentOrders(ordersRes.data?.content || []);
      } catch { /* silent */ }
      finally { setLoading(false); }
    };
    if (user) load();
  }, [user]);

  const statCards = stats ? [
    { label: isAr ? 'جديد' : 'New',              value: stats.new || 0,               icon: <ClipboardList size={22} />, color: 'text-blue-600',   bg: 'bg-blue-50 dark:bg-blue-950' },
    { label: isAr ? 'قيد المراجعة' : 'Review',   value: stats.under_review || 0,      icon: <Clock size={22} />,         color: 'text-yellow-600', bg: 'bg-yellow-50 dark:bg-yellow-950' },
    { label: isAr ? 'قيد التنفيذ' : 'In Progress',value: stats.in_progress || 0,      icon: <Wrench size={22} />,        color: 'text-orange-600', bg: 'bg-orange-50 dark:bg-orange-950' },
    { label: isAr ? 'انتظار موافقة' : 'Approval', value: stats.waiting_approval || 0, icon: <AlertTriangle size={22} />, color: 'text-red-600',    bg: 'bg-red-50 dark:bg-red-950' },
    { label: isAr ? 'انتظار مواد' : 'Materials',  value: stats.waiting_materials || 0,icon: <Package size={22} />,       color: 'text-amber-600',  bg: 'bg-amber-50 dark:bg-amber-950' },
    { label: isAr ? 'مكتمل' : 'Completed',        value: stats.completed || 0,         icon: <CheckCircle size={22} />,   color: 'text-green-600',  bg: 'bg-green-50 dark:bg-green-950' },
    { label: isAr ? 'مغلق' : 'Closed',            value: stats.closed || 0,            icon: <TrendingUp size={22} />,    color: 'text-gray-600',   bg: 'bg-gray-100 dark:bg-gray-800' },
    { label: isAr ? 'ملغي' : 'Cancelled',         value: stats.cancelled || 0,         icon: <XCircle size={22} />,       color: 'text-red-400',    bg: 'bg-red-50 dark:bg-red-950' },
  ] : [];

  const chartData = stats ? [
    { name: isAr ? 'جديد' : 'New',      value: stats.new || 0,          fill: '#3b82f6' },
    { name: isAr ? 'مراجعة' : 'Review', value: stats.under_review || 0, fill: '#eab308' },
    { name: isAr ? 'تنفيذ' : 'Progress',value: stats.in_progress || 0,  fill: '#f97316' },
    { name: isAr ? 'مكتمل' : 'Done',    value: stats.completed || 0,    fill: '#22c55e' },
    { name: isAr ? 'مغلق' : 'Closed',   value: stats.closed || 0,       fill: '#6b7280' },
  ] : [];

  const statusLabel: Record<string, string> = {
    new: isAr ? 'جديد' : 'New',
    under_review: isAr ? 'مراجعة' : 'Under Review',
    assigned: isAr ? 'مُعيَّن' : 'Assigned',
    in_progress: isAr ? 'تنفيذ' : 'In Progress',
    waiting_materials: isAr ? 'انتظار مواد' : 'Wait Materials',
    waiting_approval: isAr ? 'انتظار موافقة' : 'Wait Approval',
    completed: isAr ? 'مكتمل' : 'Completed',
    closed: isAr ? 'مغلق' : 'Closed',
    cancelled: isAr ? 'ملغي' : 'Cancelled',
  };

  if (loading) {
    return (
      <DashboardLayout>
        <div className="flex items-center justify-center h-64">
          <div className="w-10 h-10 border-4 border-primary border-t-transparent rounded-full animate-spin" />
        </div>
      </DashboardLayout>
    );
  }

  return (
    <DashboardLayout>
      <div className="max-w-7xl mx-auto space-y-6">

        {/* Header */}
        <div>
          <h1 className="text-2xl font-bold text-foreground">{tr.dashboard}</h1>
          <p className="text-sm text-muted-foreground mt-1">
            {isAr ? 'نظرة عامة على جميع طلبات الصيانة' : 'Overview of all maintenance requests'}
          </p>
        </div>

        {/* Stats Grid */}
        {stats && statCards.length > 0 && (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            {statCards.map((card) => (
              <Card key={card.label} className="shadow-sm hover:shadow-md transition-shadow">
                <CardContent className="p-4">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-xs text-muted-foreground font-medium">{card.label}</p>
                      <p className="text-3xl font-bold text-foreground mt-1">{card.value}</p>
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

        {/* Chart + Summary */}
        {stats && (
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <Card className="lg:col-span-2 shadow-sm">
              <CardHeader className="pb-2">
                <CardTitle className="text-base font-semibold">
                  {isAr ? 'توزيع البلاغات' : 'Tickets Distribution'}
                </CardTitle>
              </CardHeader>
              <CardContent>
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart data={chartData} barSize={40}>
                    <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                    <YAxis tick={{ fontSize: 12 }} allowDecimals={false} />
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

            <Card className="shadow-sm">
              <CardHeader className="pb-2">
                <CardTitle className="text-base font-semibold">
                  {isAr ? 'ملخص سريع' : 'Quick Summary'}
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {[
                  { label: isAr ? 'إجمالي النشطة' : 'Total Active', value: (stats.new||0) + (stats.under_review||0) + (stats.assigned||0) + (stats.in_progress||0), color: 'text-blue-600' },
                  { label: isAr ? 'تحتاج تدخل' : 'Needs Attention', value: (stats.waiting_approval||0) + (stats.waiting_materials||0), color: 'text-red-600' },
                  { label: isAr ? 'مكتملة ومغلقة' : 'Completed & Closed', value: (stats.completed||0) + (stats.closed||0), color: 'text-green-600' },
                  { label: isAr ? 'ملغية' : 'Cancelled', value: stats.cancelled||0, color: 'text-muted-foreground' },
                ].map((item) => (
                  <div key={item.label} className="flex justify-between items-center py-2 border-b border-border last:border-0">
                    <span className="text-sm text-muted-foreground">{item.label}</span>
                    <span className={`text-xl font-bold ${item.color}`}>{item.value}</span>
                  </div>
                ))}
              </CardContent>
            </Card>
          </div>
        )}

        {/* Recent Work Orders */}
        <Card className="shadow-sm">
          <CardHeader className="pb-2">
            <CardTitle className="text-base font-semibold">{tr.recentOrders}</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {recentOrders.length === 0 ? (
              <p className="text-center text-muted-foreground py-8 text-sm">{tr.noData}</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border">
                      {[tr.ticketNumber, tr.title, tr.status, tr.priority, tr.date].map(h => (
                        <th key={h} className="text-start px-4 py-3 font-medium text-muted-foreground">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {recentOrders.map((wo) => (
                      <tr key={wo.id} className="border-b border-border last:border-0 hover:bg-muted/30 transition-colors">
                        <td className="px-4 py-3">
                          <Link href={`/work-orders/${wo.id}`} className="font-mono text-xs text-primary font-semibold hover:underline">
                            {wo.ticketNumber}
                          </Link>
                        </td>
                        <td className="px-4 py-3 max-w-[200px] truncate text-foreground font-medium">{wo.title}</td>
                        <td className="px-4 py-3">
                          <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${statusColors[wo.status] || 'bg-gray-100'}`}>
                            {tStatus(wo.status, lang)}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          <span className={`px-2 py-0.5 rounded text-xs font-medium border ${priorityColors[wo.priority] || ''}`}>
                            {tPriority(wo.priority, lang)}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-muted-foreground text-xs">
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
