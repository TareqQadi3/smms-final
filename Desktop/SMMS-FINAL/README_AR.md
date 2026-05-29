# 🏥 نظام SMMS - دليل البدء السريع

## 📦 محتويات المجلد

```
SMMS-FINAL/
├── docker-compose.yml          ← ملف التشغيل الرئيسي
├── .env.example                ← إعدادات النظام
├── Makefile                    ← أوامر سريعة
├── scripts/                    ← ملفات قاعدة البيانات
├── nginx/                      ← إعدادات الويب
├── backend-source/             ← كود البرنامج (Java)
├── POSTMAN_COLLECTION.json     ← اختبارات API
├── QUICK_START_TESTING.md      ← دليل الاختبار
└── README_AR.md               ← هذا الملف
```

---

## ⚡ البدء السريع (3 خطوات فقط)

### ✅ الخطوة 1: فتح Terminal في هذا المجلد

**Windows:**
- اضغط زر يمين على مجلد `SMMS-FINAL`
- اختر **"Open in Terminal"** أو **"PowerShell هنا"**

**Mac:**
- افتح **Terminal** من Applications
- اكتب: `cd ` + اسحب المجلد

### ✅ الخطوة 2: نسخ ملف الإعدادات

اكتب في Terminal:
```
copy .env.example .env
```

(على Mac: `cp .env.example .env`)

### ✅ الخطوة 3: شغّل النظام

اكتب:
```
docker compose up -d
```

**انتظر دقيقتين...** ✨

---

## 🌐 فتح النظام

افتح متصفحك واكتب:
```
http://localhost
```

### بيانات الدخول:
- **Username:** `sysadmin`
- **Password:** `Admin@1234`

---

## 🧪 اختبر النظام

### الطريقة 1: Postman (الأفضل)

1. حمّل **Postman** من: https://www.postman.com/downloads/
2. افتح Postman
3. اضغط **File** → **Import**
4. اختر ملف **POSTMAN_COLLECTION.json** من هذا المجلد
5. اضغط على **Login** أولاً
6. ثم اتبع الأوامر بالترتيب

### الطريقة 2: Terminal (أسرع)

```bash
# حصول على رمز الدخول
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"sysadmin\",\"password\":\"Admin@1234\"}"
```

---

## 📋 أوامر مفيدة

```bash
# شغّل النظام
docker compose up -d

# شوف حالة الخدمات
docker compose ps

# شوف السجلات
docker compose logs -f

# أوقف النظام
docker compose down

# أعد التشغيل
docker compose restart

# نظّف كل شيء
docker compose down -v
```

---

## ✅ كيف تعرف أن كل شيء يشتغل؟

1. ✅ افتح http://localhost
2. ✅ يظهر صفحة login
3. ✅ تسجيل الدخول ينجح
4. ✅ Dashboard يظهر

---

## 🆘 مشاكل شائعة

### "Port مستخدم"
```bash
docker compose down -v
docker compose up -d
```

### "Docker لا يشتغل"
- ثبّت Docker Desktop من: https://www.docker.com/products/docker-desktop/

### "لا أستطيع الدخول"
```bash
docker compose logs backend
```

---

## 📞 الخطوات التالية

✅ اختبر النظام (تشوف Dashboard)  
✅ جرّب إنشاء تذكرة عمل  
✅ اتبع مراحل الـ Workflow  
✅ فقط بعدها نبني Frontend (واجهة الويب الجميلة)

---

**أنت جاهز الآن! 🚀**
