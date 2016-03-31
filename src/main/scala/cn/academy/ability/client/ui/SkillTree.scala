package cn.academy.ability.client.ui

import java.util

import cn.academy.ability.ModuleAbility
import cn.academy.ability.api.Skill
import cn.academy.ability.api.data.{AbilityData, CPData}
import cn.academy.ability.block.TileDeveloper
import cn.academy.ability.client.ui.Common.{RebuildEvent, TreeScreen}
import cn.academy.ability.develop.DevelopData.DevState
import cn.academy.ability.develop.action.{DevelopActionLevel, DevelopActionReset, DevelopActionSkill}
import cn.academy.ability.develop.condition.IDevCondition
import cn.academy.ability.develop.{DevelopData, DeveloperType, IDeveloper, LearningHelper}
import cn.academy.core.AcademyCraft
import cn.academy.core.client.Resources
import cn.academy.core.client.ui.{TechUI, WirelessPage}
import cn.academy.energy.api.WirelessHelper
import cn.lambdalib.annoreg.core.Registrant
import cn.lambdalib.annoreg.mc.RegInitCallback
import cn.lambdalib.cgui.gui.{CGui, CGuiScreen, Widget}
import cn.lambdalib.cgui.xml.CGUIDocument
import net.minecraft.client.Minecraft
import cn.lambdalib.cgui.ScalaCGUI._
import cn.lambdalib.cgui.gui.component.TextBox.ConfirmInputEvent
import cn.lambdalib.cgui.gui.component.Transform.{HeightAlign, WidthAlign}
import cn.lambdalib.cgui.gui.component._
import cn.lambdalib.cgui.gui.event._
import cn.lambdalib.s11n.network.NetworkMessage.Listener
import cn.lambdalib.s11n.network.{Future, NetworkMessage, NetworkS11n}
import cn.lambdalib.util.client.font.IFont.{FontAlign, FontOption}
import cn.lambdalib.util.client.shader.{ShaderMono, ShaderProgram}
import cn.lambdalib.util.client.{HudUtils, RenderUtils}
import cn.lambdalib.util.key.{KeyHandler, KeyManager}
import org.lwjgl.input.Keyboard
import cn.lambdalib.util.generic.MathUtils._
import cn.lambdalib.util.helper.{Color, GameTimer}
import cpw.mods.fml.relauncher.{Side, SideOnly}
import net.minecraft.util.{ChatAllowedCharacters, ResourceLocation}
import org.lwjgl.opengl.GL11._
import org.lwjgl.opengl.GL13._
import org.lwjgl.opengl.GL20._

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

@SideOnly(Side.CLIENT)
object DeveloperUI {

  def apply(tile: IDeveloper): CGuiScreen = {
    val ret = new TreeScreen {
      override def onGuiClosed() = tile.onGuiClosed()
    }
    implicit val gui = ret.gui()

    def build() = {
      ret.getGui.clear()
      ret.getGui.addWidget("main", Common.initialize(tile))
    }

    gui.eventBus.listen(classOf[RebuildEvent], new IGuiEventHandler[RebuildEvent] {
      override def handleEvent(w: Widget, event: RebuildEvent): Unit = build()
    })

    build()

    ret
  }

}

@SideOnly(Side.CLIENT)
object SkillTreeAppUI {
  def apply(): CGuiScreen = {
    val ret = Common.newScreen()
    implicit val gui = ret.gui()

    ret.getGui.addWidget(Common.initialize())

    ret
  }
}

@Registrant
@SideOnly(Side.CLIENT)
object SkillPosEditorUI {

  @RegInitCallback
  def __init() = {
    if (AcademyCraft.DEBUG_MODE) {
      KeyManager.dynamic.addKeyHandler("skill_tree_pos_editor", Keyboard.KEY_RMENU, new KeyHandler {
        override def onKeyDown() = {
          Minecraft.getMinecraft.displayGuiScreen(SkillPosEditorUI())
        }
      })
    }
  }

  def apply(): CGuiScreen = {
    val ret = Common.newScreen()
    implicit val gui = ret.gui()

    def build() = {
      gui.clear()

      val main = Common.initialize()

      ret.getGui.addWidget(main)

      main.removeWidget("parent_left")

      val aData = AbilityData.get(Minecraft.getMinecraft.thePlayer)
      if (aData.hasCategory) aData.getCategory.getSkillList.zipWithIndex foreach { case (skill, idx) =>
        val y = 5 + idx * 12
        val box0 = new Widget().size(40, 10).pos(20, y)
          .addComponent(Resources.newTextBox(new FontOption(8)).setContent(skill.getName))

        def box(init: Double, callback: Double => Any) = {
          val text = Resources.newTextBox(new FontOption(8)).setContent(init.toString)
          text.allowEdit()

          val ret = new Widget().size(20, 10)
            .addComponent(new DrawTexture().setTex(null).setColor4d(.3, .3, .3, .3))
            .addComponent(text)
            .listens((evt: ConfirmInputEvent) => {
              try {
                val num = text.content.toDouble
                callback(num)
                gui.eventBus.postEvent(null, new RebuildEvent)
              } catch {
                case _: NumberFormatException =>
              }
            })

          ret
        }

        val box1 = box(skill.guiX, newX => skill.guiX = newX).pos(70, y)
        val box2 = box(skill.guiY, newY => skill.guiY = newY).pos(93, y)

        gui.addWidget(box0)
        gui.addWidget(box1)
        gui.addWidget(box2)
      }
    }

    build()
    gui.eventBus.listen(classOf[RebuildEvent], new IGuiEventHandler[RebuildEvent] {
      override def handleEvent(w: Widget, event: RebuildEvent): Unit = build()
    })

    ret
  }

}

private object Common {

  private lazy val template = CGUIDocument.panicRead(Resources.getGui("rework/page_developer")).getWidget("main")

  private val texAreaBack = Resources.preloadTexture("guis/effect/effect_developer_background")
  private val texSkillBack = Resources.preloadMipmapTexture("guis/developer/skill_back")
  private val texSkillMask = Resources.preloadMipmapTexture("guis/developer/skill_radial_mask")
  private val texSkillOutline = Resources.preloadMipmapTexture("guis/developer/skill_outline")
  private val texLine = Resources.preloadMipmapTexture("guis/developer/line")
  private val texViewOutline = Resources.preloadMipmapTexture("guis/developer/skill_view_outline")
  private val texViewOutlineGlow = Resources.preloadMipmapTexture("guis/developer/skill_view_outline_glow")
  private val texButtonLearn = Resources.getTexture("guis/button/button_learn")
  private val texButtonReset = Resources.getTexture("guis/button/button_reset")
  private val texButton      = Resources.getTexture("guis/developer/button")

  private val foSkillTitle = new FontOption(12, FontAlign.CENTER)
  private val foSkillDesc = new FontOption(9, FontAlign.CENTER)
  private val foSkillProg = new FontOption(8, FontAlign.CENTER, new Color(0xffa1e1ff))
  private val foSkillUnlearned = new FontOption(10, FontAlign.CENTER, new Color(0xffff5555))
  private val foSkillUnlearned2 = new FontOption(10, FontAlign.CENTER, new Color(0xaaffffff))
  private val foSkillReq = new FontOption(9, FontAlign.RIGHT, new Color(0xaaffffff))
  private val foSkillReqDetail = new FontOption(9, FontAlign.LEFT, new Color(0xeeffffff))
  private val foSkillReqDetail2 = new FontOption(9, FontAlign.LEFT, new Color(0xffee5858))
  private val foLevelTitle = new FontOption(12, FontAlign.CENTER)
  private val foLevelReq = new FontOption(9, FontAlign.CENTER)

  private val Font = Resources.font()
  private val FontBold = Resources.fontBold()

  private val shaderProg = new ShaderProgram
  shaderProg.linkShader(Resources.getShader("skill_progbar.frag"), GL_FRAGMENT_SHADER)
  shaderProg.linkShader(Resources.getShader("skill_progbar.vert"), GL_VERTEX_SHADER)
  shaderProg.compile()

  private val shaderMono = ShaderMono.instance()

  private val posProgress = shaderProg.getUniformLocation("progress")

  shaderProg.useProgram()

  {
    glUniform1i(shaderProg.getUniformLocation("texCircle"), 0)
    glUniform1i(shaderProg.getUniformLocation("texGradient"), 1)
    glUniform1f(posProgress, 0.7f)
  }
  glUseProgram(0)

  // This event is posted on global GuiEventBus to query for widget reload. Each gui instance must by itself respond to it.
  class RebuildEvent extends GuiEvent

  def player = Minecraft.getMinecraft.thePlayer

  def initialize(developer: IDeveloper = null)(implicit gui: CGui): Widget = {
    val ret = template.copy()

    implicit val aData = AbilityData.get(player)
    implicit val developer_ = developer
    implicit val devData = DevelopData.get(player)

    val area = ret.child("parent_right/area")

    if (!aData.hasCategory) {
      initConsole(area)
    } else if (Option(player.getCurrentEquippedItem).exists(_.getItem == ModuleAbility.magneticCoil)) {
      initReset(area)
    } else { // Initialize skill area
      val back_scale = 1.01
      val back_scale_inv = 1 / back_scale
      val max_du = back_scale - 1
      val max_du_skills = 10

      var (dx, dy) = (0.0, 0.0)

      area.listens((evt: FrameEvent) => {
        val gui = area.getGui

        // Update delta
        def scale(x: Double) = (x - 0.5) * back_scale_inv + 0.5

        dx = clampd(0, 1, gui.mouseX / gui.getWidth) - 0.5
        dy = clampd(0, 1, gui.mouseY / gui.getHeight) - 0.5

        // Draw background
        RenderUtils.loadTexture(texAreaBack)
        HudUtils.rawRect(0, 0, scale(dx * max_du), scale(dy * max_du),
          area.transform.width, area.transform.height,
          back_scale_inv, back_scale_inv)
      })

      if (aData.hasCategory) {
        val skills = aData.getCategory.getSkillList.toList
          .filter(skill => LearningHelper.canBePotentiallyLearned(aData, skill))

        skills.zipWithIndex.foreach { case (skill, idx) =>
          val StateIdle = 0
          val StateHover = 1
          val TransitTime = 100.0

          val WidgetSize = 16.0
          val ProgSize = 31.0
          val TotalSize = 23.0
          val IconSize = 14.0
          val ProgAlign = (TotalSize - ProgSize) / 2
          val Align = (TotalSize - IconSize) / 2
          val DrawAlign = (WidgetSize - TotalSize) / 2

          val learned = aData.isSkillLearned(skill)

          val widget = new Widget
          val (sx, sy) = (skill.guiX, skill.guiY)

          var lastTransit = GameTimer.getTime - 2000
          var state = StateIdle
          val creationTime = GameTimer.getTime
          val blendOffset = idx * 80 + 100

          val mAlpha = (learned, if (skill.getParent == null) true else aData.isSkillLearned(skill.getParent)) match {
            case (true, _)  => 1.0
            case (_, true)  => 0.7
            case (_, false) => 0.25
          }

          val lineDrawer = Option(skill.getParent).map(parent => {
            def center(x: Double, y: Double) = (x + WidgetSize / 2, y + WidgetSize / 2)

            val (cx, cy) = center(skill.guiX, skill.guiY)
            val (pcx, pcy) = center(parent.guiX, parent.guiY)
            val (px, py) = (pcx - cx, pcy - cy)
            val norm = math.sqrt(px * px + py * py)
            val (dx, dy) = (px/norm*12.2, py/norm*12.2)

            drawLine(px + WidgetSize / 2 - dx, py + WidgetSize / 2 - dy,
              WidgetSize / 2 + dx, WidgetSize / 2 + dy, 5.5, mAlpha * (if (learned) 1.0 else 0.4))
          })

          widget.pos(sx, sy).size(WidgetSize, WidgetSize)
          widget.listens((evt: FrameEvent) => {
            val time = GameTimer.getTime

            widget.pos(sx - dx * max_du_skills, sy - dy * max_du_skills)
            widget.dirty = true

            val transitProgress = clampd(0, 1, (time - lastTransit) / TransitTime)
            val scale = state match {
              case StateIdle => lerp(1.2, 1, clampd(0, 1, transitProgress))
              case StateHover => lerp(1, 1.2, clampd(0, 1, transitProgress))
            }

            // Transit state
            if (transitProgress == 1) {
              if (state == StateIdle && evt.hovering) {
                state = StateHover
                lastTransit = GameTimer.getTime
              } else if (state == StateHover && !evt.hovering) {
                state = StateIdle
                lastTransit = GameTimer.getTime
              }
            }

            val dt = math.max(0, (time - creationTime - blendOffset) / 1000.0)
            val backAlpha = mAlpha * clampd(0, 1, dt * 10.0)
            val iconAlpha = mAlpha * clampd(0, 1, (dt - 0.08) * 10.0)
            val progressBlend = clampd(0, 1, (dt - 0.12) * 2.0).toFloat
            val lineBlend = clampd(0, 1, dt * 5.0)

            glEnable(GL_DEPTH_TEST)
            glPushMatrix()

            glTranslated(DrawAlign, DrawAlign, 10)

            glTranslated(TotalSize/2, TotalSize/2, 0)
            glScaled(scale, scale, 1)
            glTranslated(-TotalSize/2, -TotalSize/2, 0)

            // Draw back without depth writing
            glColor4d(1, 1, 1, backAlpha)
            glDepthMask(false)
            RenderUtils.loadTexture(texSkillBack)
            HudUtils.rect(0, 0, TotalSize, TotalSize)

            // Draw outline back
            RenderUtils.loadTexture(texSkillOutline)
            glColor4d(0.2, 0.2, 0.2, backAlpha * 0.6)
            HudUtils.rect(ProgAlign, ProgAlign, ProgSize, ProgSize)
            glColor4f(1, 1, 1, 1)

            // Draw back as a depth mask
            glDepthMask(true)
            glEnable(GL_ALPHA_TEST)
            glColorMask(false, false, false, false)
            glAlphaFunc(GL_GREATER, 0.3f)

            RenderUtils.loadTexture(texSkillBack)
            HudUtils.rect(0, 0, TotalSize, TotalSize)

            glPushMatrix()
            glTranslated(0, 0, 1)
            RenderUtils.loadTexture(texSkillOutline)
            glAlphaFunc(GL_GREATER, 0.5f)
            HudUtils.rect(ProgAlign, ProgAlign, ProgSize, ProgSize)
            glPopMatrix()

            glDisable(GL_ALPHA_TEST)
            glColorMask(true, true, true, true)
            glDepthMask(false)

            // Draw skill
            glColor4d(1, 1, 1, iconAlpha)
            glDepthFunc(GL_EQUAL)
            RenderUtils.loadTexture(skill.getHintIcon)
            if (!learned) {
              glUseProgram(shaderMono.getProgramID)
            }
            HudUtils.rect(Align, Align, IconSize, IconSize)
            glUseProgram(0)
            glDepthFunc(GL_LEQUAL)

            // Progress bar (if learned)
            glColor4d(1, 1, 1, 1)
            if (learned) {
              glDisable(GL_DEPTH_TEST)

              shaderProg.useProgram()
              glUniform1f(posProgress, progressBlend * aData.getSkillExp(skill))

              glActiveTexture(GL_TEXTURE0)
              RenderUtils.loadTexture(texSkillOutline)

              glActiveTexture(GL_TEXTURE1)
              RenderUtils.loadTexture(texSkillMask)

              glActiveTexture(GL_TEXTURE0)
              HudUtils.rect(ProgAlign, ProgAlign, ProgSize, ProgSize)

              glUseProgram(0)
              glEnable(GL_DEPTH_TEST)
            }

            glPopMatrix()

            glDepthFunc(GL_NOTEQUAL)
            glPushMatrix()
            glTranslated(0, 0, 11)
            lineDrawer match {
              case Some(drawer) => drawer(lineBlend)
              case _ =>
            }
            glPopMatrix()

            glDepthFunc(GL_LEQUAL)
            glDisable(GL_DEPTH_TEST)
          })

          widget.listens[LeftClickEvent](() => {
            val cover = skillViewArea(skill)

            widget.getGui.addWidget(cover)
          })


          area :+ widget
        }
      }
    }

    { // Initialize left ability panel
      val panel = ret.child("parent_left/panel_ability")

      val (icon, name, prog, lvltext) = Option(aData.getCategoryNullable) match {
        case Some(cat) => (cat.getDeveloperIcon, cat.getDisplayName, math.max(0.02f, CPData.get(player).getLevelProgress), "Level " + aData.getLevel)
        case None => (Resources.getTexture("guis/icons/icon_nonecat"), "No Category", 0.0f, "")
      }

      panel.child("logo_ability").component[DrawTexture].setTex(icon)
      panel.child("text_abilityname").component[TextBox].setContent(name)
      panel.child("logo_progress").component[ProgressBar].progress = prog
      panel.child("text_level").component[TextBox].setContent(lvltext)

      if (developer != null && aData.hasCategory && LearningHelper.canLevelUp(developer.getType, aData)) {
        val btn = panel.child("btn_upgrade")
        btn.transform.doesDraw = true
        btn.listens[LeftClickEvent](() => {
          val cover = levelUpArea

          gui.addWidget(cover)
        })
      }
    }

    { // Initialize machine panel
      val panel = ret.child("parent_left/panel_machine")

      val wProgPower = panel.child("progress_power")
      val progPower = wProgPower.component[ProgressBar]

      val wProgRate = panel.child("progress_syncrate")
      val progRate = wProgRate.component[ProgressBar]

      val wirelessButton = panel.child("button_wireless")

      if (developer != null) {
        wProgPower.listens[FrameEvent](() => {
          progPower.progress = developer.getEnergy / developer.getMaxEnergy
        })
        progRate.progress = developer.getType.syncRate
        developer match {
          case tile: TileDeveloper =>
            send(NetDelegate.MSG_GET_NODE, tile, Future.create((result: String) => {
              panel.child("button_wireless/text_nodename").component[TextBox].content = if (result != null) result else "N/A"
            }))
            panel.child("button_wireless").listens[LeftClickEvent](() => {
              val wirelessPage = WirelessPage.userPage(tile).window.centered()
              val cover = blackCover(gui)
              cover :+ wirelessPage

              cover.listens[LeftClickEvent](() => gui.eventBus.postEvent(null, new RebuildEvent))

              gui.addWidget(cover)
            })
          case _ =>
            panel.child("button_wireless").transform.doesDraw = false
            panel.child("text_wireless").transform.doesDraw = false
        }

      } else {
        panel.transform.doesDraw = false
      }
    }

    ret
  }

  private def drawLine(x0: Double, y0: Double, x1: Double, y1: Double,
                       width: Double, alpha: Double): (Double)=>Any = {
    val (dx, dy) = (x1 - x0, y1 - y0)
    val norm = math.sqrt(dx * dx + dy * dy)
    val (nx, ny) = (-dy/norm/2*width, dx/norm/2*width)

    (progress) => {
      val (xx, yy) = (lerp(x0, x1, progress), lerp(y0, y1, progress))

      RenderUtils.loadTexture(texLine)
      glColor4d(1, 1, 1, alpha)

      glBegin(GL_QUADS)

      glTexCoord2d(0, 0)
      glVertex2d(x0 - nx, y0 - ny)

      glTexCoord2d(0, 1)
      glVertex2d(x0 + nx, y0 + ny)

      glTexCoord2d(1, 1)
      glVertex2d(xx + nx, yy + ny)

      glTexCoord2d(1, 0)
      glVertex2d(xx - nx, yy - ny)

      glEnd()
    }
  }

  private def normalize(x: Double, absmax: Double) = math.min(math.abs(x), absmax) * math.signum(x)

  private def blackCover(gui: CGui): Widget = {
    val ret = new Widget
    ret :+ new Cover
    ret.size(gui.getWidth, gui.getHeight)

    ret
  }

  private def levelUpArea(implicit data: AbilityData, gui: CGui, developer: IDeveloper): Widget = {
    val ret = blackCover(gui)

    {
      val wid = new Widget
      wid.centered().size(50, 50)

      val action = new DevelopActionLevel()
      val estmCons = LearningHelper.getEstimatedConsumption(player, developer.getType, action)

      val textArea = new Widget().size(0, 10).centered().pos(0, 25)

      var hint = "Continue?"
      var progress: Double = 0
      var canClose: Boolean = true
      var shouldRebuild = false

      val icon = Resources.getTexture("abilities/condition/any" + (data.getLevel+1))

      wid.listens[FrameEvent](() => {
        drawActionIcon(icon, progress, glow = progress == 1)
      })

      val lvltext = s"Upgrade to Level ${data.getLevel+1}"
      val reqtext = s"Req. $estmCons"
      textArea.listens[FrameEvent](() => {
        Font.draw(lvltext, 0, 3, foLevelTitle)
        Font.draw(reqtext, 0, 16, foLevelReq)
        Font.draw(hint, 0, 26, foLevelReq)
      })

      val button = newButton().centered().pos(0, 40)
      button.listens[LeftClickEvent](() => {
        if (developer.getEnergy < estmCons) {
          hint = "Not enough energy."
        } else {
          val devData = DevelopData.get(player)
          devData.reset()
          canClose = false

          send(NetDelegate.MSG_START_LEVEL, devData, developer)
          ret.listens[FrameEvent](() => devData.getState match {
            case DevState.IDLE =>

            case DevState.DEVELOPING =>
              hint = "Developing..."
              progress = devData.getDevelopProgress

            case DevState.DONE =>
              hint = "Develop successful."
              progress = 1
              canClose = true
              shouldRebuild = true

            case DevState.FAILED =>
              hint = "Develop failed."
              canClose = true
          })
        }

        button.dispose()
      })

      textArea :+ button
      ret :+ textArea
      ret.listens[LeftClickEvent](() => {
        if (canClose) {
          if (shouldRebuild) {
            gui.eventBus.postEvent(null, new RebuildEvent)
          } else {
            ret.component[Cover].end()
          }
        }
      })
      ret :+ wid
    }

    ret
  }

  private def skillViewArea(skill: Skill)
                           (implicit data: AbilityData, gui: CGui, developer: IDeveloper=null): Widget = {
    val ret = blackCover(gui)

    {
      val skillWid = new Widget
      skillWid.centered().size(50, 50)

      val learned = data.isSkillLearned(skill)
      var canClose = true
      var shouldRebuild = false

      val textArea = new Widget().size(0, 10).centered().pos(0, 25)
      if (learned) {
        skillWid.listens[FrameEvent](() => {
          drawActionIcon(skill.getHintIcon, 0, glow=false)
        })
        textArea.listens[FrameEvent](() => {
          FontBold.draw(skill.getDisplayName, 0, 3, foSkillTitle)
          Font.draw("Skill Experience: %.0f%%".format(data.getSkillExp(skill) * 100), 0, 15, foSkillProg)
          Font.drawSeperated(skill.getDescription, 0, 24, 200, foSkillDesc)
        })
      } else {
        var progress: Double = 0
        var message: Option[String] = None

        skillWid.listens[FrameEvent](() => {
          drawActionIcon(skill.getHintIcon, progress, glow=progress == 1)
        })

        textArea.listens[FrameEvent](() => {
          FontBold.draw(skill.getDisplayName, 0, 3, foSkillTitle)
          Font.draw("Skill Not Learned", 0, 15, foSkillUnlearned)
        })

        if (developer != null) {
          val action = new DevelopActionSkill(skill)
          val estmCons = LearningHelper.getEstimatedConsumption(player, developer.getType, action)

          val conditions = skill.getDevConditions.toList.filter(_.shouldDisplay)
          val CondIconSize = 14
          val CondIconStep = 16
          val len = CondIconStep * conditions.size

          textArea.listens[FrameEvent](() => {
            Font.draw("Req.", -len/2 - 2, 26, foSkillReq)
          })

          case class CondTag(cond: IDevCondition, accepted: Boolean) extends Component("CondTag")

          conditions.zipWithIndex foreach { case (cond, idx) =>
            val widget = new Widget().size(CondIconSize, CondIconSize)
              .pos(-len/2 + CondIconStep * idx, 25).size(CondIconSize, CondIconSize)

            val tex = new DrawTexture(cond.getIcon)
            val accepted = cond.accepts(data, developer, skill)

            if (!accepted) {
              tex.setShaderId(shaderMono.getProgramID)
            }

            widget :+ tex

            widget :+ CondTag(cond, accepted)
            textArea :+ widget
          }

          textArea.listens[FrameEvent](() => {
            Option(gui.getHoveringWidget) match {
              case Some(w) =>
                val tag = Option(w.component[CondTag])
                tag match {
                  case Some(CondTag(cond, accepted)) =>
                    Font.draw(s"(${cond.getHintText})", len/2 + 3, 27, if (accepted) foSkillReqDetail else foSkillReqDetail2)
                  case _ =>
                }
              case _ =>
            }
          })

          textArea.listens[FrameEvent](() => message match {
            case Some(str) =>
              Font.draw(str, 0, 40, foSkillUnlearned2)
            case None =>
              Font.draw("Learn? (Estm. Consumption: " + estmCons + ")",
                0, 40, foSkillUnlearned2)
          })

          val button = newButton().centered().pos(0, 55)

          button.listens[LeftClickEvent](() => {
            if (developer.getEnergy < estmCons) {
              message = Some("Not enough energy.")
            } else if (!action.validate(player, developer)) {
              message = Some("Develop condition not satisfied.")
            } else {
              // start developing
              val devData = DevelopData.get(player)
              devData.reset()

              send(NetDelegate.MSG_START_SKILL, devData, developer, skill)
              canClose = false
              ret.listens[FrameEvent](() => {
                devData.getState match {
                  case DevState.IDLE =>
                  case DevState.DEVELOPING =>
                    message = Some("Progress %.0f%%".format(devData.getDevelopProgress * 100))
                    progress = devData.getDevelopProgress
                  case DevState.DONE =>
                    message = Some("Develop successful.")
                    shouldRebuild = true
                    progress = 1.0
                    canClose = true

                  case DevState.FAILED =>
                    canClose = true
                    message = Some("Develop failed.")
                }
              })
            }

            button.dispose()
          })

          textArea :+ button
        }
      }

      ret :+ textArea
      ret :+ skillWid

      ret.listens[LeftClickEvent](() => if (canClose) {
        if (shouldRebuild) {
          gui.eventBus.postEvent(null, new RebuildEvent)
        } else {
          ret.component[Cover].end()
        }
      })
    }

    ret
  }

  private def newButton() = new Widget()
    .size(64, 32).scale(.5)
    .addComponent(new DrawTexture(texButton))
    .addComponent(new Tint(Color.monoBlend(1, .6), Color.monoBlend(1, 1), true))

  private def drawActionIcon(icon: ResourceLocation, progress: Double, glow: Boolean) = {
    val BackSize = 50
    val IconSize = 27
    val IconAlign = (BackSize - IconSize) / 2

    glPushMatrix()
    glTranslated(0, 0, 11)
    glColor4f(1, 1, 1, 1)

    RenderUtils.loadTexture(texSkillBack)
    HudUtils.rect(0, 0, BackSize, BackSize)

    RenderUtils.loadTexture(icon)
    HudUtils.rect(IconAlign, IconAlign, IconSize, IconSize)

    glUseProgram(shaderProg.getProgramID)

    glActiveTexture(GL_TEXTURE1)
    RenderUtils.loadTexture(texSkillMask)

    glActiveTexture(GL_TEXTURE0)
    RenderUtils.loadTexture(if (glow) texViewOutlineGlow else texViewOutline)

    glUniform1f(posProgress, progress.toFloat)
    HudUtils.rect(0, 0, BackSize, BackSize)

    glUseProgram(0)

    glPopMatrix()
  }

  private def fmt(x: Int) = if (x < 10) "0" + x else x

  private def initConsole(area: Widget)(implicit data: DevelopData, developer: IDeveloper) = {
    implicit val console = new Console(false)

    console += Command("learn", () => {

      console.enqueue(printTask("Start stimulation......\n"))
      console.enqueue(printTask("Progress 00%"))
      send(NetDelegate.MSG_START_LEVEL, data, developer)
      data.reset()

      console.enqueue(new Task {
        override def isFinished: Boolean = data.getState == DevState.FAILED || data.getState == DevState.DONE
        override def update() = {
          console.output("\b\b\b" + fmt((data.getDevelopProgress*100).toInt) + "%")
        }
        override def finish() = {
          console.outputln()
          if (data.getState == DevState.DONE) {
            console.output("Develop successful.\n")
          } else {
            console.output("Develop failed." + "\n")
          }

          console.pause(500)
          console.enqueueRebuild()
        }
      })
    })

    area :+ console
  }

  private def initReset(area: Widget)(implicit data: DevelopData, developer: IDeveloper) = {
    implicit val console = new Console(true)

    console += Command("reset", () => {
      if (DevelopActionReset.canReset(data.getEntity, developer)) {
        console.enqueue(printTask("Try resetting ability ..."))
        console.enqueue(printTask("Progress: 00%"))
        send(NetDelegate.MSG_RESET, data, developer)
        data.reset()

        console.enqueue(new Task {
          override def isFinished: Boolean = data.getState == DevState.FAILED || data.getState == DevState.DONE
          override def update() = {
            console.output("\b\b\b" + fmt((data.getDevelopProgress*100).toInt) + "%")
          }
          override def finish() = {
            console.outputln()
            if (data.getState == DevState.DONE) {
              console.output("Reset successful.\n")
            } else {
              console.output("Reset failed.\n")
            }

            console.pause(500)
            console.enqueueRebuild()
          }
        })
      } else {
        if (developer.getType != DeveloperType.ADVANCED) {
          console.enqueue(printTask("Can't reset ability -- Advance Developer must be used.\n"))
        } else {
          console.enqueue(printTask("Can't reset ability -- either level is too low or induction factor not detected.\n"))
        }
      }
    })

    area :+ console
  }

  class Cover extends Component("cover") {

    private var lastTransit = GameTimer.getTime
    private var ended: Boolean = false

    this.listens[FrameEvent](() => {
      val time = GameTimer.getTime
      val dt = time - lastTransit

      widget.transform.width = widget.getGui.getWidth
      widget.transform.height = widget.getGui.getHeight

      val src = clampd(0, 1, dt / 200.0)
      val alpha = if (ended) 1 - src else src

      glColor4d(0, 0, 0, alpha * 0.7)
      HudUtils.colorRect(0, 0, widget.transform.width, widget.transform.height)

      if (ended && alpha == 0) {
        widget.dispose()
      }

      widget.dirty = true
    })

    def end() = {
      ended = true
      lastTransit = GameTimer.getTime
    }

  }

  def newScreen(): CGuiScreen = new TreeScreen()

  class TreeScreen extends CGuiScreen {
    // getGui.setDebug()

    override def doesGuiPauseGame = false
  }

  object Console {
    private val MaxLines = 12
    private val FO = new FontOption(8)
    private val Help1 =
      """|Welcome to Academy OS, Ver 1.0.0
         |Copyright (c) Academy Tech. All rights reserved.
         |User %s detected,
         |System booting......""".stripMargin

    private val Help2 =
      """|
         |FATAL: User's ability category is invalid, booting aborted.
         |Use `learn` command to acquire new category.
         |
         |""".stripMargin

    private val Help3 =
      """|
         |WARNING: System override! External injection detected.....
         |
         |ABILITY RESET: Use `reset` command to reset player category.
         |""".stripMargin

    private val ConsoleHead = "OS >"
  }

  class Console(val emergency: Boolean)
    extends Component("Console") {
    import Console._

    private implicit val _self = this

    private val inputTask = new Task {
      override def finish(): Unit = {}
      override def update(): Unit = {}
      override def isFinished: Boolean = false
      override def begin(): Unit = {
        output(ConsoleHead)
      }
    }

    private val commands = new ArrayBuffer[Command]()
    private val outputs: util.Deque[String] = new util.LinkedList[String]()
    private val taskQueue: util.Queue[Task] = new util.LinkedList[Task]()
    private var currentTask: Task = null
    private var input: String = ""

    enqueue(slowPrintTask(Help1.format(Minecraft.getMinecraft.thePlayer.getCommandSenderName)))
    pause(400)
    animSequence(300, "10%", "20%", "30%", "40%", "50%", "60%", "64%", "Boot Failed.\n")
    enqueue(slowPrintTask(if (emergency) Help3 else Help2))

    this.listens[FrameEvent](() => {
      if (currentTask != null && currentTask.isFinished) {
        currentTask.finish()
        currentTask = null
      }

      if (currentTask != null) {
        currentTask.update()
      }

      if (currentTask == null) {
        if (taskQueue.isEmpty) {
          currentTask = inputTask
        } else {
          currentTask = taskQueue.remove()
        }
        currentTask.begin()
      }

      val x = 5
      val font = Resources.font()
      var y = 5

      outputs.zipWithIndex foreach { case (line, idx) =>
        if (idx == outputs.size() - 1 && currentTask == inputTask) {
          font.draw(line + input + (if (GameTimer.getTime % 1000 < 500) "_" else ""), x, y, FO)
        } else {
          font.draw(line, x, y, FO)
        }

        y += 10
      }

      widget.gainFocus()
    })

    this.listens((evt: KeyEvent) => {
      if (evt.keyCode == Keyboard.KEY_BACK) {
        if (input.length > 0) {
          input = input.substring(0, input.length - 1)
        }
      } else if (evt.keyCode == Keyboard.KEY_RETURN || evt.keyCode == Keyboard.KEY_NUMPADENTER) {
        parseCommand(input)
        input = ""
      } else if (ChatAllowedCharacters.isAllowedCharacter(evt.inputChar)) {
        input += evt.inputChar
      }
    })

    def enqueue(task: Task) = {
      taskQueue.offer(task)
      if (currentTask == inputTask) {
        outputln(input)
        currentTask = null
      }
    }

    def output(content: String) = {
      println(s"output $content, current=${outputs.peekLast()}" )
      def refresh() = new StringBuilder(if (outputs.isEmpty) "" else outputs.removeLast())

      var current = refresh()

      def flush() = {
        outputs.addLast(current.toString())
      }

      for (ch <- content) ch match {
        case '\b' => current.setLength(math.max(0, current.length - 1))
        case '\n' =>
          flush()
          outputs.addLast("")
          current = refresh()
        case _ => current += ch
      }

      flush()

      while (outputs.size > MaxLines) outputs.removeFirst()

      println(s"output2 $content, current=$outputs" )
    }

    def outputln(content: String) = {
      output(content + '\n')
    }

    def outputln() = output("\n")

    def animSequence(time: Long, strs: String*) = {
      for ((s, idx) <- strs.zipWithIndex) {
        enqueue(new TimedTask {
          override def life = time

          override def finish(): Unit = if (idx != strs.length - 1) {
            output("\b" * s.length)
          }

          override def update(): Unit = {}

          override def begin(): Unit = {
            super.begin()
            output(s)
          }
        })
      }
    }

    def pause(time: Long) = enqueue(new TimedTask {
      override def life: Long = time
    })

    def enqueueRebuild() = enqueue(new Task {
      override def isFinished: Boolean = true
      override def begin(): Unit = widget.getGui.eventBus.postEvent(null, new RebuildEvent)
    })

    def += (command: Command) = {
      commands += command
    }

    private def parseCommand(cmd: String) = {
      commands.filter(_.name == cmd).toList match {
        case Nil => enqueue(printTask("Invalid command.\n"))
        case command :: _ => command.callback()
      }
    }

  }

  case class Command(name: String, callback: ()=>Any)
  trait Task {
    def begin(): Unit = {}
    def update(): Unit = {}
    def finish(): Unit = {}

    def isFinished: Boolean
  }
  trait TimedTask extends Task {
    def life: Long

    private var creationTime: Long = -1

    def getCreationTime = creationTime

    override def begin() = creationTime = GameTimer.getTime

    override def isFinished = GameTimer.getTime - creationTime >= life
  }

  def printTask(str: String)(implicit console: Console): Task = new Task {
    override def begin(): Unit = {
      console.output(str)
    }
    override def isFinished = true
  }

  def slowPrintTask(str: String)(implicit console: Console): Task = new Task {
    val PerCharTime = 20

    private var idx = 0
    private var last = -1L

    override def finish(): Unit = {}

    override def begin(): Unit = {
      last = GameTimer.getTime
    }

    override def update(): Unit = {
      val time = GameTimer.getTime
      val n = (time - last).toInt / PerCharTime
      if (n > 0) {
        val end = math.min(str.length, idx + n)
        console.output(str.substring(idx, end))

        last += n * PerCharTime
        idx = end
      }
    }

    override def isFinished: Boolean = idx == str.length
  }

  private def send(channel: String, pars: Any*) = NetworkMessage.sendToServer(NetDelegate, channel, pars.map(_.asInstanceOf[AnyRef]): _*)

}

@Registrant
private object NetDelegate {

  final val MSG_START_SKILL = "start_skill"
  final val MSG_GET_NODE = "get_node"
  final val MSG_RESET = "reset"
  final val MSG_START_LEVEL = "start_level"

  @RegInitCallback
  def __init() = {
    NetworkS11n.addDirectInstance(NetDelegate)
  }

  @Listener(channel=MSG_START_SKILL, side=Array(Side.SERVER))
  private def hStartSkill(data: DevelopData, developer: IDeveloper, skill: Skill) = {
    data.startDeveloping(developer, new DevelopActionSkill(skill))
  }

  @Listener(channel=MSG_START_LEVEL, side=Array(Side.SERVER))
  private def hStartLevel(data: DevelopData, developer: IDeveloper) = {
    data.startDeveloping(developer, new DevelopActionLevel())
  }

  @Listener(channel=MSG_GET_NODE, side=Array(Side.SERVER))
  private def hGetLinkNodeName(tile: TileDeveloper, future: Future[String]) = {
    future.sendResult(WirelessHelper.getNodeConn(tile) match {
      case null => null
      case conn => conn.getNode.getNodeName
    })
  }

  @Listener(channel=MSG_RESET, side=Array(Side.SERVER))
  private def hStartReset(data: DevelopData, developer: IDeveloper) = {
    data.startDeveloping(developer, new DevelopActionReset)
  }

}
