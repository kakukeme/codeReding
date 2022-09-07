package f;

import f.i0.c;
import g.e;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

public final class s
{
  public static final char[] a = { 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 65, 66, 67, 68, 69, 70 };
  public final String b;
  public final String c;
  public final String d;
  public final String e;
  public final int f;
  @Nullable
  public final List<String> g;
  @Nullable
  public final String h;
  public final String i;
  
  public s(a parama)
  {
    this.b = parama.a;
    this.c = l(parama.b, false);
    this.d = l(parama.c, false);
    this.e = parama.d;
    int j = parama.e;
    if (j == -1) {
      j = d(parama.a);
    }
    this.f = j;
    m(parama.f, false);
    Object localObject1 = parama.g;
    Object localObject2 = null;
    if (localObject1 != null) {
      localObject1 = m((List)localObject1, true);
    } else {
      localObject1 = null;
    }
    this.g = ((List)localObject1);
    String str = parama.h;
    localObject1 = localObject2;
    if (str != null) {
      localObject1 = k(str, 0, str.length(), false);
    }
    this.h = ((String)localObject1);
    this.i = parama.toString();
  }
  
  public static String a(String paramString1, int paramInt1, int paramInt2, String paramString2, boolean paramBoolean1, boolean paramBoolean2, boolean paramBoolean3, boolean paramBoolean4)
  {
    int j = paramInt1;
    while (j < paramInt2)
    {
      int k = paramString1.codePointAt(j);
      if ((k >= 32) && (k != 127) && ((k < 128) || (!paramBoolean4)) && (paramString2.indexOf(k) == -1) && ((k != 37) || ((paramBoolean1) && ((!paramBoolean2) || (n(paramString1, j, paramInt2))))) && ((k != 43) || (!paramBoolean3)))
      {
        j += Character.charCount(k);
      }
      else
      {
        e locale = new e();
        locale.S(paramString1, paramInt1, j);
        Object localObject3;
        for (Object localObject1 = null; j < paramInt2; localObject1 = localObject3)
        {
          k = paramString1.codePointAt(j);
          if (paramBoolean1)
          {
            localObject3 = localObject1;
            if (k == 9) {
              break label414;
            }
            localObject3 = localObject1;
            if (k == 10) {
              break label414;
            }
            localObject3 = localObject1;
            if (k == 12) {
              break label414;
            }
            if (k == 13)
            {
              localObject3 = localObject1;
              break label414;
            }
          }
          Object localObject2;
          if ((k == 43) && (paramBoolean3))
          {
            if (paramBoolean1) {
              localObject2 = "+";
            } else {
              localObject2 = "%2B";
            }
            locale.R((String)localObject2);
            localObject3 = localObject1;
          }
          else if ((k >= 32) && (k != 127) && ((k < 128) || (!paramBoolean4)) && (paramString2.indexOf(k) == -1) && ((k != 37) || ((paramBoolean1) && ((!paramBoolean2) || (n(paramString1, j, paramInt2))))))
          {
            locale.T(k);
            localObject3 = localObject1;
          }
          else
          {
            localObject2 = localObject1;
            if (localObject1 == null) {
              localObject2 = new e();
            }
            ((e)localObject2).T(k);
            for (;;)
            {
              localObject3 = localObject2;
              if (((e)localObject2).o()) {
                break;
              }
              paramInt1 = ((e)localObject2).readByte() & 0xFF;
              locale.M(37);
              localObject1 = a;
              locale.M(localObject1[(paramInt1 >> 4 & 0xF)]);
              locale.M(localObject1[(paramInt1 & 0xF)]);
            }
          }
          label414:
          j += Character.charCount(k);
        }
        return locale.G();
      }
    }
    return paramString1.substring(paramInt1, paramInt2);
  }
  
  public static String b(String paramString1, String paramString2, boolean paramBoolean1, boolean paramBoolean2, boolean paramBoolean3, boolean paramBoolean4)
  {
    return a(paramString1, 0, paramString1.length(), paramString2, paramBoolean1, paramBoolean2, paramBoolean3, paramBoolean4);
  }
  
  public static int c(char paramChar)
  {
    if ((paramChar >= '0') && (paramChar <= '9')) {
      return paramChar - '0';
    }
    char c1 = 'a';
    if ((paramChar >= 'a') && (paramChar <= 'f')) {}
    do
    {
      return paramChar - c1 + 10;
      c1 = 'A';
    } while ((paramChar >= 'A') && (paramChar <= 'F'));
    return -1;
  }
  
  public static int d(String paramString)
  {
    if (paramString.equals("http")) {
      return 80;
    }
    if (paramString.equals("https")) {
      return 443;
    }
    return -1;
  }
  
  public static void j(StringBuilder paramStringBuilder, List<String> paramList)
  {
    int k = paramList.size();
    for (int j = 0; j < k; j += 2)
    {
      String str1 = (String)paramList.get(j);
      String str2 = (String)paramList.get(j + 1);
      if (j > 0) {
        paramStringBuilder.append('&');
      }
      paramStringBuilder.append(str1);
      if (str2 != null)
      {
        paramStringBuilder.append('=');
        paramStringBuilder.append(str2);
      }
    }
  }
  
  public static String k(String paramString, int paramInt1, int paramInt2, boolean paramBoolean)
  {
    int j = paramInt1;
    while (j < paramInt2)
    {
      int k = paramString.charAt(j);
      if ((k != 37) && ((k != 43) || (!paramBoolean)))
      {
        j++;
      }
      else
      {
        e locale = new e();
        locale.S(paramString, paramInt1, j);
        paramInt1 = j;
        while (paramInt1 < paramInt2)
        {
          k = paramString.codePointAt(paramInt1);
          if (k == 37)
          {
            j = paramInt1 + 2;
            if (j < paramInt2)
            {
              int n = c(paramString.charAt(paramInt1 + 1));
              int m = c(paramString.charAt(j));
              if ((n == -1) || (m == -1)) {
                break label172;
              }
              locale.M((n << 4) + m);
              paramInt1 = j;
              break label180;
            }
          }
          if ((k == 43) && (paramBoolean)) {
            locale.M(32);
          } else {
            label172:
            locale.T(k);
          }
          label180:
          paramInt1 += Character.charCount(k);
        }
        return locale.G();
      }
    }
    return paramString.substring(paramInt1, paramInt2);
  }
  
  public static String l(String paramString, boolean paramBoolean)
  {
    return k(paramString, 0, paramString.length(), paramBoolean);
  }
  
  public static boolean n(String paramString, int paramInt1, int paramInt2)
  {
    int j = paramInt1 + 2;
    boolean bool = true;
    if ((j >= paramInt2) || (paramString.charAt(paramInt1) != '%') || (c(paramString.charAt(paramInt1 + 1)) == -1) || (c(paramString.charAt(j)) == -1)) {
      bool = false;
    }
    return bool;
  }
  
  public static List<String> o(String paramString)
  {
    ArrayList localArrayList = new ArrayList();
    int k;
    for (int j = 0; j <= paramString.length(); j = k + 1)
    {
      int m = paramString.indexOf('&', j);
      k = m;
      if (m == -1) {
        k = paramString.length();
      }
      m = paramString.indexOf('=', j);
      if ((m != -1) && (m <= k))
      {
        localArrayList.add(paramString.substring(j, m));
        localArrayList.add(paramString.substring(m + 1, k));
      }
      else
      {
        localArrayList.add(paramString.substring(j, k));
        localArrayList.add(null);
      }
    }
    return localArrayList;
  }
  
  public String e()
  {
    if (this.d.isEmpty()) {
      return "";
    }
    int j = this.i.indexOf(':', this.b.length() + 3);
    int k = this.i.indexOf('@');
    return this.i.substring(j + 1, k);
  }
  
  public boolean equals(@Nullable Object paramObject)
  {
    boolean bool;
    if (((paramObject instanceof s)) && (((s)paramObject).i.equals(this.i))) {
      bool = true;
    } else {
      bool = false;
    }
    return bool;
  }
  
  public String f()
  {
    int j = this.i.indexOf('/', this.b.length() + 3);
    String str = this.i;
    int k = c.e(str, j, str.length(), "?#");
    return this.i.substring(j, k);
  }
  
  public List<String> g()
  {
    int j = this.i.indexOf('/', this.b.length() + 3);
    Object localObject = this.i;
    int k = c.e((String)localObject, j, ((String)localObject).length(), "?#");
    localObject = new ArrayList();
    while (j < k)
    {
      int m = j + 1;
      j = c.d(this.i, m, k, '/');
      ((ArrayList)localObject).add(this.i.substring(m, j));
    }
    return (List<String>)localObject;
  }
  
  @Nullable
  public String h()
  {
    if (this.g == null) {
      return null;
    }
    int k = this.i.indexOf('?') + 1;
    String str = this.i;
    int j = c.d(str, k + 1, str.length(), '#');
    return this.i.substring(k, j);
  }
  
  public int hashCode()
  {
    return this.i.hashCode();
  }
  
  public String i()
  {
    if (this.c.isEmpty()) {
      return "";
    }
    int k = this.b.length() + 3;
    String str = this.i;
    int j = c.e(str, k, str.length(), ":@");
    return this.i.substring(k, j);
  }
  
  public final List<String> m(List<String> paramList, boolean paramBoolean)
  {
    int k = paramList.size();
    ArrayList localArrayList = new ArrayList(k);
    for (int j = 0; j < k; j++)
    {
      String str = (String)paramList.get(j);
      if (str != null) {
        str = k(str, 0, str.length(), paramBoolean);
      } else {
        str = null;
      }
      localArrayList.add(str);
    }
    return Collections.unmodifiableList(localArrayList);
  }
  
  public URI p()
  {
    Object localObject2 = new a();
    ((a)localObject2).a = this.b;
    ((a)localObject2).b = i();
    ((a)localObject2).c = e();
    ((a)localObject2).d = this.e;
    if (this.f != d(this.b)) {
      j = this.f;
    } else {
      j = -1;
    }
    ((a)localObject2).e = j;
    ((a)localObject2).f.clear();
    ((a)localObject2).f.addAll(g());
    ((a)localObject2).d(h());
    if (this.h == null)
    {
      localObject1 = null;
    }
    else
    {
      j = this.i.indexOf('#');
      localObject1 = this.i.substring(j + 1);
    }
    ((a)localObject2).h = ((String)localObject1);
    int m = ((a)localObject2).f.size();
    int k = 0;
    for (int j = 0; j < m; j++)
    {
      localObject1 = (String)((a)localObject2).f.get(j);
      ((a)localObject2).f.set(j, b((String)localObject1, "[]", true, true, false, true));
    }
    Object localObject1 = ((a)localObject2).g;
    if (localObject1 != null)
    {
      m = ((List)localObject1).size();
      for (j = k; j < m; j++)
      {
        localObject1 = (String)((a)localObject2).g.get(j);
        if (localObject1 != null) {
          ((a)localObject2).g.set(j, b((String)localObject1, "\\^`{|}", true, true, true, true));
        }
      }
    }
    localObject1 = ((a)localObject2).h;
    if (localObject1 != null) {
      ((a)localObject2).h = b((String)localObject1, " \"#<>\\^`{|}", true, true, false, false);
    }
    localObject2 = ((a)localObject2).toString();
    RuntimeException localRuntimeException;
    try
    {
      localObject1 = new URI((String)localObject2);
      return (URI)localObject1;
    }
    catch (URISyntaxException localURISyntaxException)
    {
      try
      {
        localObject2 = URI.create(((String)localObject2).replaceAll("[\\u0000-\\u001F\\u007F-\\u009F\\p{javaWhitespace}]", ""));
        return (URI)localObject2;
      }
      catch (Exception localException)
      {
        localRuntimeException = new RuntimeException(localURISyntaxException);
      }
    }
    for (;;)
    {
      throw localRuntimeException;
    }
  }
  
  public String toString()
  {
    return this.i;
  }
  
  public static final class a
  {
    @Nullable
    public String a;
    public String b = "";
    public String c = "";
    @Nullable
    public String d;
    public int e = -1;
    public final List<String> f;
    @Nullable
    public List<String> g;
    @Nullable
    public String h;
    
    public a()
    {
      ArrayList localArrayList = new ArrayList();
      this.f = localArrayList;
      localArrayList.add("");
    }
    
    public static String b(String paramString, int paramInt1, int paramInt2)
    {
      int n = 0;
      paramString = s.k(paramString, paramInt1, paramInt2, false);
      if (paramString.contains(":"))
      {
        if ((paramString.startsWith("[")) && (paramString.endsWith("]"))) {
          paramString = c(paramString, 1, paramString.length() - 1);
        } else {
          paramString = c(paramString, 0, paramString.length());
        }
        if (paramString == null) {
          return null;
        }
        byte[] arrayOfByte = paramString.getAddress();
        if (arrayOfByte.length == 16)
        {
          paramInt2 = -1;
          paramInt1 = 0;
          int i = 0;
          int j;
          while (paramInt1 < arrayOfByte.length)
          {
            for (j = paramInt1; (j < 16) && (arrayOfByte[j] == 0) && (arrayOfByte[(j + 1)] == 0); j += 2) {}
            int i1 = j - paramInt1;
            int m = i;
            int k = paramInt2;
            if (i1 > i)
            {
              m = i;
              k = paramInt2;
              if (i1 >= 4)
              {
                m = i1;
                k = paramInt1;
              }
            }
            paramInt1 = j + 2;
            i = m;
            paramInt2 = k;
          }
          paramString = new e();
          paramInt1 = n;
          while (paramInt1 < arrayOfByte.length) {
            if (paramInt1 == paramInt2)
            {
              paramString.M(58);
              j = paramInt1 + i;
              paramInt1 = j;
              if (j == 16)
              {
                paramString.M(58);
                paramInt1 = j;
              }
            }
            else
            {
              if (paramInt1 > 0) {
                paramString.M(58);
              }
              paramString.O((arrayOfByte[paramInt1] & 0xFF) << 8 | arrayOfByte[(paramInt1 + 1)] & 0xFF);
              paramInt1 += 2;
            }
          }
          return paramString.G();
        }
        throw new AssertionError();
      }
      return c.g(paramString);
    }
    
    @Nullable
    public static InetAddress c(String paramString, int paramInt1, int paramInt2)
    {
      byte[] arrayOfByte = new byte[16];
      int k = paramInt1;
      paramInt1 = 0;
      int j = -1;
      int i = -1;
      int m;
      int n;
      for (;;)
      {
        m = paramInt1;
        n = j;
        if (k >= paramInt2) {
          break label459;
        }
        if (paramInt1 == 16) {
          return null;
        }
        n = k + 2;
        if ((n <= paramInt2) && (paramString.regionMatches(k, "::", 0, 2)))
        {
          if (j != -1) {
            return null;
          }
          m = paramInt1 + 2;
          paramInt1 = m;
          if (n == paramInt2)
          {
            n = paramInt1;
            break label459;
          }
          i = n;
          j = paramInt1;
        }
        else
        {
          m = k;
          if (paramInt1 != 0) {
            if (paramString.regionMatches(k, ":", 0, 1))
            {
              m = k + 1;
            }
            else
            {
              if (paramString.regionMatches(k, ".", 0, 1))
              {
                int i1 = paramInt1 - 2;
                m = i1;
                k = i;
                while (k < paramInt2)
                {
                  if (m == 16) {
                    break label313;
                  }
                  i = k;
                  if (m != i1)
                  {
                    if (paramString.charAt(k) != '.') {
                      break label313;
                    }
                    i = k + 1;
                  }
                  k = i;
                  n = 0;
                  while (k < paramInt2)
                  {
                    int i2 = paramString.charAt(k);
                    if ((i2 < 48) || (i2 > 57)) {
                      break;
                    }
                    if ((n == 0) && (i != k)) {
                      break label313;
                    }
                    n = n * 10 + i2 - 48;
                    if (n > 255) {
                      break label313;
                    }
                    k++;
                  }
                  if (k - i == 0) {
                    break label313;
                  }
                  arrayOfByte[m] = ((byte)n);
                  m++;
                }
                if (m != i1 + 4) {
                  label313:
                  paramInt2 = 0;
                } else {
                  paramInt2 = 1;
                }
                if (paramInt2 == 0) {
                  return null;
                }
                m = paramInt1 + 2;
                n = j;
                break label459;
              }
              return null;
            }
          }
          i = m;
          m = paramInt1;
        }
        paramInt1 = i;
        k = 0;
        while (paramInt1 < paramInt2)
        {
          n = s.c(paramString.charAt(paramInt1));
          if (n == -1) {
            break;
          }
          k = (k << 4) + n;
          paramInt1++;
        }
        n = paramInt1 - i;
        if ((n == 0) || (n > 4)) {
          break;
        }
        n = m + 1;
        arrayOfByte[m] = ((byte)(k >>> 8 & 0xFF));
        m = n + 1;
        arrayOfByte[n] = ((byte)(k & 0xFF));
        k = paramInt1;
        paramInt1 = m;
      }
      return null;
      label459:
      if (m != 16)
      {
        if (n == -1) {
          return null;
        }
        paramInt1 = m - n;
        System.arraycopy(arrayOfByte, n, arrayOfByte, 16 - paramInt1, paramInt1);
        Arrays.fill(arrayOfByte, n, 16 - m + n, (byte)0);
      }
      try
      {
        paramString = InetAddress.getByAddress(arrayOfByte);
        return paramString;
      }
      catch (UnknownHostException paramString)
      {
        paramString = new AssertionError();
      }
      for (;;)
      {
        throw paramString;
      }
    }
    
    public s a()
    {
      if (this.a != null)
      {
        if (this.d != null) {
          return new s(this);
        }
        throw new IllegalStateException("host == null");
      }
      throw new IllegalStateException("scheme == null");
    }
    
    public a d(@Nullable String paramString)
    {
      if (paramString != null) {
        paramString = s.o(s.b(paramString, " \"'<>#", true, false, true, true));
      } else {
        paramString = null;
      }
      this.g = paramString;
      return this;
    }
    
    public a e(@Nullable s params, String paramString)
    {
      int i = c.r(paramString, 0, paramString.length());
      int i1 = c.s(paramString, i, paramString.length());
      if (i1 - i < 2) {}
      label171:
      for (;;)
      {
        j = -1;
        break;
        j = paramString.charAt(i);
        if (((j >= 97) && (j <= 122)) || ((j >= 65) && (j <= 90)))
        {
          j = i;
          for (;;)
          {
            j++;
            if (j >= i1) {
              break label171;
            }
            k = paramString.charAt(j);
            if (((k < 97) || (k > 122)) && ((k < 65) || (k > 90)) && ((k < 48) || (k > 57)) && (k != 43) && (k != 45) && (k != 46))
            {
              if (k != 58) {
                break;
              }
              break label173;
            }
          }
        }
      }
      label173:
      if (j != -1)
      {
        if (paramString.regionMatches(true, i, "https:", 0, 6))
        {
          this.a = "https";
          i += 6;
        }
        else if (paramString.regionMatches(true, i, "http:", 0, 5))
        {
          this.a = "http";
          i += 5;
        }
        else
        {
          return a.UNSUPPORTED_SCHEME;
        }
      }
      else
      {
        if (params == null) {
          break label1419;
        }
        this.a = params.b;
      }
      int j = i;
      int k = 0;
      int m;
      while (j < i1)
      {
        m = paramString.charAt(j);
        if ((m != 92) && (m != 47)) {
          break;
        }
        k++;
        j++;
      }
      int n;
      Object localObject1;
      if ((k < 2) && (params != null) && (params.b.equals(this.a)))
      {
        this.b = params.i();
        this.c = params.e();
        this.d = params.e;
        this.e = params.f;
        this.f.clear();
        this.f.addAll(params.g());
        if (i != i1)
        {
          j = i;
          if (paramString.charAt(i) != '#') {}
        }
        else
        {
          d(params.h());
          j = i;
        }
      }
      else
      {
        k = i + k;
        i = 0;
        m = 0;
        for (;;)
        {
          j = c.e(paramString, k, i1, "@/\\?#");
          if (j != i1) {
            n = paramString.charAt(j);
          } else {
            n = -1;
          }
          if ((n == -1) || (n == 35) || (n == 47) || (n == 92) || (n == 63)) {
            break;
          }
          if (n == 64)
          {
            if (i == 0)
            {
              int i2 = c.d(paramString, k, j, ':');
              n = j;
              localObject1 = s.a(paramString, k, i2, " \"':;<=>@[]^`{}|/\\?#", true, false, false, true);
              params = (s)localObject1;
              if (m != 0)
              {
                params = new StringBuilder();
                params.append(this.b);
                params.append("%40");
                params.append((String)localObject1);
                params = params.toString();
              }
              this.b = params;
              if (i2 != n)
              {
                this.c = s.a(paramString, i2 + 1, n, " \"':;<=>@[]^`{}|/\\?#", true, false, false, true);
                i = 1;
              }
              m = 1;
            }
            else
            {
              params = new StringBuilder();
              params.append(this.c);
              params.append("%40");
              params.append(s.a(paramString, k, j, " \"':;<=>@[]^`{}|/\\?#", true, false, false, true));
              this.c = params.toString();
            }
            k = j + 1;
          }
        }
        for (i = k; i < j; i = m + 1)
        {
          n = paramString.charAt(i);
          m = i;
          if (n == 58) {
            break label758;
          }
          if (n != 91)
          {
            m = i;
          }
          else
          {
            m = i;
            do
            {
              i = m + 1;
              m = i;
              if (i >= j) {
                break;
              }
              m = i;
            } while (paramString.charAt(i) != ']');
            m = i;
          }
        }
        m = j;
        label758:
        i = m + 1;
        if (i < j) {
          this.d = b(paramString, k, m);
        }
      }
      try
      {
        i = Integer.parseInt(s.a(paramString, i, j, "", false, false, false, true));
        if ((i <= 0) || (i > 65535)) {}
      }
      catch (NumberFormatException params)
      {
        for (;;) {}
      }
      i = -1;
      this.e = i;
      if (i == -1)
      {
        return a.INVALID_PORT;
        this.d = b(paramString, k, m);
        this.e = s.d(this.a);
      }
      if (this.d == null) {
        return a.INVALID_HOST;
      }
      i = c.e(paramString, j, i1, "?#");
      if (j != i)
      {
        k = paramString.charAt(j);
        if ((k != 47) && (k != 92))
        {
          params = this.f;
          params.set(params.size() - 1, "");
          localObject1 = this;
          m = i;
          params = paramString;
        }
        else
        {
          this.f.clear();
          this.f.add("");
          localObject1 = this;
          m = i;
          params = paramString;
        }
        for (;;)
        {
          j++;
          do
          {
            if (j >= m) {
              break;
            }
            k = c.e(params, j, m, "/\\");
            if (k < m) {
              n = 1;
            } else {
              n = 0;
            }
            Object localObject2 = s.a(params, j, k, " \"<>^`{}|/\\?#", true, false, false, true);
            if ((!((String)localObject2).equals(".")) && (!((String)localObject2).equalsIgnoreCase("%2e"))) {
              j = 0;
            } else {
              j = 1;
            }
            if (j == 0)
            {
              if ((!((String)localObject2).equals("..")) && (!((String)localObject2).equalsIgnoreCase("%2e.")) && (!((String)localObject2).equalsIgnoreCase(".%2e")) && (!((String)localObject2).equalsIgnoreCase("%2e%2e"))) {
                j = 0;
              } else {
                j = 1;
              }
              if (j != 0)
              {
                localObject2 = ((a)localObject1).f;
                if ((((String)((List)localObject2).remove(((List)localObject2).size() - 1)).isEmpty()) && (!((a)localObject1).f.isEmpty()))
                {
                  localObject2 = ((a)localObject1).f;
                  ((List)localObject2).set(((List)localObject2).size() - 1, "");
                }
                else
                {
                  ((a)localObject1).f.add("");
                }
              }
              else
              {
                List localList = ((a)localObject1).f;
                if (((String)localList.get(localList.size() - 1)).isEmpty())
                {
                  localList = ((a)localObject1).f;
                  localList.set(localList.size() - 1, localObject2);
                }
                else
                {
                  ((a)localObject1).f.add(localObject2);
                }
                if (n != 0) {
                  ((a)localObject1).f.add("");
                }
              }
            }
            j = k;
          } while (n == 0);
          j = k;
        }
      }
      if ((i < i1) && (paramString.charAt(i) == '?'))
      {
        j = c.d(paramString, i, i1, '#');
        this.g = s.o(s.a(paramString, i + 1, j, " \"'<>#", true, false, true, true));
        i = j;
      }
      if ((i < i1) && (paramString.charAt(i) == '#')) {
        this.h = s.a(paramString, 1 + i, i1, "", true, false, false, false);
      }
      return a.SUCCESS;
      label1419:
      return a.MISSING_SCHEME;
    }
    
    public String toString()
    {
      StringBuilder localStringBuilder = new StringBuilder();
      localStringBuilder.append(this.a);
      localStringBuilder.append("://");
      if ((!this.b.isEmpty()) || (!this.c.isEmpty()))
      {
        localStringBuilder.append(this.b);
        if (!this.c.isEmpty())
        {
          localStringBuilder.append(':');
          localStringBuilder.append(this.c);
        }
        localStringBuilder.append('@');
      }
      if (this.d.indexOf(':') != -1)
      {
        localStringBuilder.append('[');
        localStringBuilder.append(this.d);
        localStringBuilder.append(']');
      }
      else
      {
        localStringBuilder.append(this.d);
      }
      int i = this.e;
      if (i == -1) {
        i = s.d(this.a);
      }
      if (i != s.d(this.a))
      {
        localStringBuilder.append(':');
        localStringBuilder.append(i);
      }
      List localList = this.f;
      int j = localList.size();
      for (i = 0; i < j; i++)
      {
        localStringBuilder.append('/');
        localStringBuilder.append((String)localList.get(i));
      }
      if (this.g != null)
      {
        localStringBuilder.append('?');
        s.j(localStringBuilder, this.g);
      }
      if (this.h != null)
      {
        localStringBuilder.append('#');
        localStringBuilder.append(this.h);
      }
      return localStringBuilder.toString();
    }
    
    public static enum a
    {
      static
      {
        a locala5 = new a("SUCCESS", 0);
        SUCCESS = locala5;
        a locala3 = new a("MISSING_SCHEME", 1);
        MISSING_SCHEME = locala3;
        a locala1 = new a("UNSUPPORTED_SCHEME", 2);
        UNSUPPORTED_SCHEME = locala1;
        a locala4 = new a("INVALID_PORT", 3);
        INVALID_PORT = locala4;
        a locala2 = new a("INVALID_HOST", 4);
        INVALID_HOST = locala2;
        a = new a[] { locala5, locala3, locala1, locala4, locala2 };
      }
    }
  }
}


/* Location:              /Users/kokia/Desktop/android/classes-d2j.jar!/f/s.class
 * Java compiler version: 6 (50.0)
 * JD-Core Version:       0.7.1
 */