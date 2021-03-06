package com.wf.ew.system.controller;

import cn.jiguang.common.resp.APIConnectionException;
import cn.jiguang.common.resp.APIRequestException;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.wangfan.endecrypt.utils.EndecryptUtils;
import com.wf.captcha.utils.CaptchaUtil;
import com.wf.ew.common.BaseController;
import com.wf.ew.common.JsonResult;
import com.wf.ew.common.config.ErrorCodeConfig;
import com.wf.ew.common.exception.BusinessException;
import com.wf.ew.common.utils.StringUtil;
import com.wf.ew.jmsgUtils.JmsgUserUtils;
import com.wf.ew.system.model.Authorities;
import com.wf.ew.system.model.Menu;
import com.wf.ew.system.model.User;
import com.wf.ew.system.model.UserRole;
import com.wf.ew.system.service.*;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.apache.catalina.servlet4preview.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.wf.jwtp.provider.Token;
import org.wf.jwtp.provider.TokenStore;

import javax.servlet.http.HttpServletResponse;
import java.util.*;

@Api(value = "个人信息", tags = "main")
@RequestMapping("${api.version}/")
@Controller
public class MainController extends BaseController {
    @Autowired
    private UserService userService;
    @Autowired
    private TokenStore tokenStore;
    @Autowired
    private AuthoritiesService authoritiesService;
    @Autowired
    private MenuService menuService;
    @Autowired
    private UserRoleService userRoleService;

    /*@ApiIgnore
    @RequestMapping({"/", "/index"})
    public String index() {
        return "redirect:index.html";
    }*/

    @RequestMapping("/user/captcha")
    public void captcha(HttpServletRequest request, HttpServletResponse response) throws Exception {
        CaptchaUtil.out(request, response);
    }

    @ResponseBody
    @ApiOperation(value = "获取个人信息")
    @ApiImplicitParam(name = "access_token", value = "令牌", required = true, dataType = "String", paramType = "query")
    @PostMapping("user/info")
    public JsonResult userInfo(HttpServletRequest request) {
        User user = userService.selectById(getLoginUserId(request));
        return JsonResult.ok().put("user", user);
    }



    @ResponseBody
    @ApiOperation(value = "用户注册")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "username", value = "账号", required = true, dataType = "String", paramType = "form"),
            @ApiImplicitParam(name = "password", value = "密码", required = true, dataType = "String", paramType = "form")
    })
    @PostMapping("user/register")
    public JsonResult register(String username, String password) {
        System.out.println("用户名:"+username+password);
        if(StringUtil.isBlank(username)){
            return JsonResult.error("账号不能为空");
        }
        if(StringUtil.isBlank(password)){
            return JsonResult.error("密码不能为空");
        }
        User suser = userService.getByUsername(username);
        if (suser == null) {
            String roleIds = "1";
            String[] split = roleIds.split(",");
            User user = new User();
            user.setUsername(username);
            user.setPassword(EndecryptUtils.encrytMd5(password));
            user.setSex("女");
            user.setBirthday(new Date());
            if (userService.insert(user)) {
                List<UserRole> userRoles = new ArrayList<>();
                for (String roleId : split) {
                    UserRole userRole;
                    userRole = new UserRole();
                    userRole.setRoleId(Integer.parseInt(roleId));
                    userRole.setUserId(user.getUserId());
                    userRoles.add(userRole);
                }
                if (!userRoleService.insertBatch(userRoles)) {
                    throw new BusinessException("用户添加失败");
                }

                try {
                    JmsgUserUtils.register(username);
                    return JsonResult.ok(ErrorCodeConfig.USER_REGISTER_SUCCESS_CONTENT);
                } catch (APIConnectionException e) {
                    throw new BusinessException("添加Jmsg失败APIConnectionException");
                } catch (APIRequestException e) {
                    throw new BusinessException("添加Jmsg失败APIRequestException");
                }
            } else {
                return JsonResult.error(ErrorCodeConfig.USER_REGISTER,ErrorCodeConfig.USER_REGISTER_CONTENT);
            }
        }else{
            return JsonResult.error(ErrorCodeConfig.USER_EXIST,ErrorCodeConfig.USER_EXIST_CONTENT);
        }

    }
    @ResponseBody
    @ApiOperation(value = "用户登录")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "username", value = "账号", required = true, dataType = "String", paramType = "form"),
            @ApiImplicitParam(name = "password", value = "密码", required = true, dataType = "String", paramType = "form"),
            @ApiImplicitParam(name = "code", value = "验证码", required = true, dataType = "String", paramType = "form")
    })
    @PostMapping("user/login")
    public JsonResult login(String username, String password,String code) {
        System.out.println("用户名:"+username);
        if(StringUtil.isBlank(username)){
            return JsonResult.error("账号不能为空");
        }
        if(StringUtil.isBlank(password)){
            return JsonResult.error("密码不能为空");
        }
        User user = userService.getByUsername(username);
        if (user == null) {
            return JsonResult.error("账号不存在");
        } else if (!user.getPassword().equals(EndecryptUtils.encrytMd5(password))) {
            return JsonResult.error("密码错误");
        } else if (user.getState() != 0) {
            return JsonResult.error("账号被锁定");
        }
        String[] roles = arrayToString(userRoleService.getRoleIds(user.getUserId()));
        String[] permissions = listToArray(authoritiesService.listByUserId(user.getUserId()));
        Token token = tokenStore.createNewToken(String.valueOf(user.getUserId()), permissions, roles);

        return JsonResult.ok("登录成功").put("access_token", token.getAccessToken()).put("userinfo",user);
    }






    @ResponseBody
    @ApiOperation(value = "获取所有菜单")
    @ApiImplicitParam(name = "access_token", value = "令牌", required = true, dataType = "String", paramType = "query")
    @GetMapping("user/menu")
    public JsonResult userMenu(HttpServletRequest request) {
        // 获取当前用户的权限
        Token token = getLoginToken(request);
        String[] auths = token.getPermissions();
        // 查询所有的菜单
        List<Menu> menus = menuService.selectList(new EntityWrapper<Menu>().orderBy("sort_number", true));
        // 移除没有权限的菜单
        Iterator<Menu> iterator = menus.iterator();
        while (iterator.hasNext()) {
            Menu next = iterator.next();
            boolean haveAuth = false;
            for (String auth : auths) {
                if (StringUtil.isBlank(next.getAuthority()) || next.getAuthority().equals(auth)) {
                    haveAuth = true;
                }
            }
            if (!haveAuth) {
                iterator.remove();
            }
        }
        // 去除空的目录
        iterator = menus.iterator();
        while (iterator.hasNext()) {
            Menu next = iterator.next();
            if (StringUtil.isBlank(next.getMenuUrl())) {
                boolean haveSub = false;
                for (Menu t : menus) {
                    if (t.getParentId() == next.getMenuId()) {
                        haveSub = true;
                        break;
                    }
                }
                if (!haveSub) {
                    iterator.remove();
                }
            }
        }
        return JsonResult.ok().put("data", getMenuTree(menus, -1));
    }

    // 递归转化树形菜单
    private List<Map<String, Object>> getMenuTree(List<Menu> menus, Integer parentId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < menus.size(); i++) {
            Menu temp = menus.get(i);
            if (parentId == temp.getParentId()) {
                Map<String, Object> map = new HashMap<>();
                map.put("name", temp.getMenuName());
                map.put("icon", temp.getMenuIcon());
                map.put("url", StringUtil.isBlank(temp.getMenuUrl()) ? "javascript:;" : temp.getMenuUrl());
                map.put("subMenus", getMenuTree(menus, menus.get(i).getMenuId()));
                list.add(map);
            }
        }
        return list;
    }

    private String[] listToArray(List<String> list) {
        String[] strs = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            strs[i] = list.get(i);
        }
        return strs;
    }

    private String[] arrayToString(Object[] objs) {
        String[] strs = new String[objs.length];
        for (int i = 0; i < objs.length; i++) {
            strs[i] = String.valueOf(objs[i]);
        }
        return strs;
    }

}
