import { CSSProperties } from 'react';
import {
  DARK_BLUE_COLOR,
  GREY_COLOR,
  LARGE_FONT,
  LIGHT_GREY_COLOR,
  MEDIUM_FONT,
} from '../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  pluginName: {
    color: DARK_BLUE_COLOR,
    fontSize: LARGE_FONT,
    marginRight: '10px',
    marginTop: '2px',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
  },
  centerFlex: {
    flex: '1',
  },
  modifiedDate: {
    color: GREY_COLOR,
    fontSize: '13px',
    marginTop: '2px',
  },
  content: {
    marginTop: '30px',
    borderBottom: '2px solid ' + LIGHT_GREY_COLOR,
    paddingBottom: '5px',
  },
  topContent: {
    display: 'flex',
    height: '36px',
  },
  bottomContent: {
    marginTop: '5px',
    marginRight: '50px',
  },
  text: {
    maxWidth: '600px',
  },
  truncate: {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  descriptionContainer: {
    display: 'flex',
    color: GREY_COLOR,
    fontSize: '13px',
    maxWidth: '600px',
  },
  descriptionButton: {
    fontSize: '13px',
    color: DARK_BLUE_COLOR,
    whiteSpace: 'nowrap',
  },
  versionSelect: {
    position: 'relative',
    marginTop: '-1px',
    marginRight: '10px',
  },
};
export default styles;
