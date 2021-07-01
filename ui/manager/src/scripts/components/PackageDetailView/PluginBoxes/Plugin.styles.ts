import { makeStyles } from '@material-ui/core/styles';
import {
  BLUE_COLOR,
  GREY_BORDER,
  GREY_COLOR,
  LIGHT_BLUE_COLOR3,
  LIGHT_GREY_BORDER,
  LIGHT_GREY_COLOR,
  MEDIUM_FONT,
  RED_BORDER,
  RED_COLOR,
  SMALL_FONT,
  SMALL_FONT2,
  WHITE_COLOR,
  YELLOW_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  pluginCenter: {
    flex: 1,
  },
  pluginContainer: {
    display: 'flex',
    flexDirection: 'column',
    minWidth: '100%',
  },
  pluginWithExtensionsContainer: {
    display: 'inline-block',
    minWidth: '100%',
    marginTop: '10px',
  },
  extensionContainer: {
    display: 'inline-block',
  },
  updateAvailableBorderColor: {
    border: RED_BORDER,
  },
  updateAvailableTextColor: {
    color: RED_COLOR,
  },
  pluginBox: {
    backgroundColor: 'transparent',
    border: LIGHT_GREY_BORDER,
    borderRadius: '4px',
    outline: 'none',
    padding: '0px',
    width: '100%',
    height: '80px',
    marginTop: '-5px',
  },
  clickablePluginBox: {
    '&:hover': {
      border: `1px solid ${BLUE_COLOR}`,
    },
    cursor: 'pointer',
  },
  pluginBoxWithExtensions: {
    border: LIGHT_GREY_BORDER,
    borderRadius: '4px',
    outline: 'none',
    padding: '0px',
    width: '100%',
    marginTop: '-5px',
  },
  extensionBox: {
    backgroundColor: 'transparent',
    border: LIGHT_GREY_BORDER,
    borderRadius: '4px',
    cursor: 'pointer',
    outline: 'none',
    padding: '0px',
    width: '100%',
    height: '80px',
    marginTop: '-5px',
  },
  bigPluginName: {
    display: 'flex',
    height: '22px',
    marginLeft: '5px',
    marginRight: '5px',
    marginTop: '15px',
  },
  centerFlex: {
    flex: 1,
  },
  addExtensionButton: {
    fontSize: SMALL_FONT,
    textDecoration: 'underline',
    color: BLUE_COLOR,
    marginTop: '5px',
  },
  addResourceButton: {
    '&:hover': {
      color: YELLOW_COLOR,
    },
    cursor: 'pointer',
    whiteSpace: 'nowrap',
    marginRight: '5px',
    marginLeft: 'auto',
    marginTop: '3px',
    fontSize: SMALL_FONT,
    textDecoration: 'underline',
    textAlign: 'right',
    color: BLUE_COLOR,
    width: 'fit-content',
  },
  pluginHeader: {
    display: 'flex',
    height: '22px',
    marginLeft: '5px',
    marginRight: '5px',
  },
  pluginName: {
    color: BLUE_COLOR,
    fontSize: MEDIUM_FONT,
    overflow: 'hidden',
    textAlign: 'left',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  pluginVersion: {
    color: GREY_COLOR,
    fontSize: SMALL_FONT,
    lineHeight: '21px',
    marginLeft: '2px',
    textAlign: 'left',
    marginRight: '5px',
  },
  pluginDate: {
    color: WHITE_COLOR,
    fontSize: SMALL_FONT,
    marginLeft: '5px',
    marginTop: '5px',
    textAlign: 'left',
  },
  updateAvailableButton: {
    '&:hover': {
      boxShadow: '0 0 3px #0070d2',
    },
    backgroundColor: '#ffffff',
    border: LIGHT_GREY_BORDER,
    borderRadius: '4px',
    color: BLUE_COLOR,
    display: 'block',
    fontSize: SMALL_FONT,
    height: '34px',
    lineHeight: '30px',
    marginLeft: 'auto',
    marginRight: '5px',
    marginTop: '-40px',
    position: 'relative',
    textAlign: 'center',
    whiteSpace: 'nowrap',
    width: '94px',
    cursor: 'pointer',
  },

  updateAvailablePluginNameAndVersion: {
    marginRight: '100px',
  },

  hasNewVersionBorder: {
    border: RED_BORDER,
  },

  hasNewVersion: {
    color: RED_COLOR,
  },

  closeButton: {
    float: 'right',
    marginRight: '10px',
  },
  packageWithExtensionCloseButton: {
    marginRight: '10px',
    marginLeft: 'auto',
  },
  extensionList: {
    display: 'grid',
    marginTop: '10px',
    marginLeft: '10px',
    marginRight: '10px',
    marginBottom: '10px',
    gridGap: '10px 20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
  chatRelations: {
    display: 'flex',
    flexDirection: 'column',
    paddingLeft: '10px',
    marginTop: '10px',
    borderLeft: '1px solid black',
  },
  chatRelation: {
    display: 'flex',
    fontSize: SMALL_FONT,
    color: LIGHT_GREY_COLOR,
  },
  rjv: {
    border: GREY_BORDER,
    borderRadius: '5px',
    fontSize: SMALL_FONT2,
  },
});

export const rjvStyles = {
  rjv: {
    border: GREY_BORDER,
    borderRadius: '5px',
    fontSize: SMALL_FONT2,
  },
};

export default useStyles;
