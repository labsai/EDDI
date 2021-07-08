import * as React from 'react';
import { makeStyles } from '@material-ui/core/styles';
import {
  SMALL_FONT,
  SMALL_FONT2,
  WHITE_COLOR,
  YELLOW_COLOR,
} from '../../../../../styles/DefaultStylingProperties';
import * as _ from 'lodash';

const useStyles = makeStyles({
  treeContainer: {
    '& *': {
      color: WHITE_COLOR,
      fontFamily: 'Monaco',
      fontSize: SMALL_FONT,
    },
    '& ul': {
      listStyleType: 'none',
      paddingLeft: '10px',
    },

    '& .node': {
      cursor: 'pointer',
      userSelect: 'none' /* Prevent text selection */,
    },

    '& .toggler': {
      cursor: 'pointer',
      display: 'inline-block',
      userSelect: 'none' /* Prevent text selection */,

      /* Create the caret/arrow with a unicode, and style it */
      '&:before': {
        content: '"+"',
        fontSize: '17px',
        color: '#ae81fe',
        marginRight: '6px',
        border: `1px solid #ae81fe`,
        lineHeight: '12px',
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        height: '13px',
        width: '14px',
        paddingBottom: '2px',
        paddingLeft: '1px',
        borderRadius: '2px',
      },
    },

    /* Rotate the caret/arrow icon when clicked on (using JavaScript) */
    '& .node-down': {
      '&:before': {
        content: '"-"!important',
        border: `1px solid #65d9ef!important`,
        color: '#65d9ef!important',
        paddingBottom: '2px!important',
      },
    },

    '& .dots': {
      display: 'block',
      color: YELLOW_COLOR,
      fontWeight: 'bold',
    },

    /* Hide the nested list */
    '& .nested': {
      display: 'inline-flex',
      paddingLeft: '0',

      '& > div': {
        display: 'none',
      },
      '& > li': {
        display: 'none',
      },
      '& > span': {
        display: 'block',
      },
    },

    /* Show the nested list when the user clicks on the caret/arrow (with JavaScript) */
    '& .active': {
      display: 'block',
      paddingLeft: '10px!important',

      '& > div': {
        display: 'block!important',
      },
      '& > li': {
        display: 'block!important',
      },
      '& > .dots': {
        display: 'none!important',
      },
      '& > .brackets': {
        marginLeft: '-10px',
      },
    },

    '& .leaf': {
      color: YELLOW_COLOR,
    },
  },
  toggle: {
    display: 'flex',
    flexDirection: 'row',
    alignItems: 'center',
  },
  empty: {
    opacity: 0.6,
  },
  object: {
    paddingLeft: '10px',

    '& > span': {
      marginLeft: '-10px',
    },
  },
});

export const rjvStyles = {
  rjv: {
    borderRadius: '5px',
    fontSize: SMALL_FONT2,
    marginLeft: '10px',
  },
};

const RecursiveTreeView = ({ data }) => {
  const classes = useStyles();

  const processObject = (object) => {
    if (!object) {
      return;
    }
    return Object.keys(object).map((key, reactKey) => {
      if (key === 'id') {
        return;
      }
      if (isPrimative(object[key])) {
        return (
          <li key={key + reactKey}>
            {buildNodeNoToggle(key, `"${object[key]}"`, object.id)}
          </li>
        );
      }
      if (isArray(object[key]) && _.isEmpty(object[key])) {
        return (
          <li key={key + reactKey}>
            {buildNodeNoToggleObject(key, '[]', object.id)}
          </li>
        );
      }
      if (isObject(object[key]) && !Object.keys(object[key]).length) {
        return (
          <li key={key + reactKey}>
            {buildNodeNoToggleObject(key, '{}', object.id)}
          </li>
        );
      }
      return (
        <li key={key + reactKey}>
          {buildNode(key, object.id)}{' '}
          <ul className="nested">
            <strong className="brackets">{`${
              isArray(object[key]) ? '[' : isObject(object[key]) ? '{' : ''
            }`}</strong>
            {isPrimative(object[key])
              ? buildLeaf(object[key])
              : isArray(object[key])
              ? loopArray(object[key])
              : processObject(object[key])}
            <strong className="dots">{`${
              isArray(object[key]) && !_.isEmpty(object[key])
                ? '...'
                : isObject(object[key]) && Object.keys(object[key]).length
                ? '...'
                : ''
            }`}</strong>
            <strong className="brackets">{`${
              isArray(object[key]) ? ']' : isObject(object[key]) ? '}' : ''
            }`}</strong>
          </ul>
        </li>
      );
    });
  };

  const loopArray = (array) =>
    array.map((value, key: number) => (
      <div key={key} data-elemenindex={key}>
        {isPrimative(value) ? (
          buildLeaf(value)
        ) : isArray(value) ? (
          loopArray(value)
        ) : (
          <div className={classes.object}>
            <span>{`{`}</span>
            {processObject(value)}
            <span>{array.length - 1 === key ? `}` : `},`}</span>
          </div>
        )}
      </div>
    ));
  const isArray = (value) => Array.isArray(value);
  const isObject = (value) => typeof value === 'object';

  const isPrimative = (value) => {
    return (
      typeof value === 'string' ||
      typeof value === 'number' ||
      typeof value === 'boolean'
    );
  };

  let parentName: string;
  let elementIndex: number;

  // find parent element name to navigate to right element in case of nodes with the same name
  const findParentUlElement = (element) => {
    const target = element;
    if (
      target?.parentElement?.localName === 'div' &&
      target?.parentElement?.dataset.elemenindex
    ) {
      elementIndex = target?.parentElement?.dataset.elemenindex;
    }
    if (target?.parentElement?.localName === 'ul') {
      const parent =
        target.parentElement.parentElement.childNodes[1]?.outerText;
      if (parent) {
        parentName = parent;
        return parentName?.replace?.(':', '');
      }
    } else {
      if (target?.parentElement) {
        findParentUlElement(target?.parentElement);
      } else {
        parentName = null;
        elementIndex = null;
        return;
      }
    }
  };

  // scroll to element with specific id or key
  const handleScrollToElement = (
    e: React.MouseEvent<HTMLSpanElement>,
    id?: string,
    key?: string,
  ) => {
    if (!id && !key) {
      return;
    }

    findParentUlElement(e.target);

    const parent = parentName?.replace(':', '');
    const element = document.getElementById(id);
    const elements = document.querySelectorAll(`[id$='${key}']`);
    if (element) {
      element.scrollIntoView({ behavior: 'smooth' });
      return;
    } else if (elements.length) {
      const elementsArray = Array.from(elements);
      const directElement = elementsArray.find((e) =>
        e.id.includes(`${parent}_${elementIndex}`),
      );
      if (directElement) {
        directElement.scrollIntoView({ behavior: 'smooth' });
      } else {
        elementsArray[0].scrollIntoView({ behavior: 'smooth' });
      }
    }
  };

  const buildNode = (key: string, id?: string) => (
    <>
      <span
        className="toggler"
        onClick={(e) => {
          toggle(e);
        }}></span>
      <span
        className="node"
        onClick={(e) => {
          handleScrollToElement(e, id, key);
        }}>
        {key}:
      </span>
    </>
  );

  const buildNodeNoToggle = (key: string, value: string, id?: string) => (
    <span
      className="node"
      onClick={(e) => {
        handleScrollToElement(e, id, key);
      }}>
      {key}: <span className="leaf">{value}</span>
    </span>
  );

  const buildNodeNoToggleObject = (key: string, value: string, id?: string) => (
    <span
      className="node"
      onClick={(e) => {
        handleScrollToElement(e, id, key);
      }}>
      {key}: <span className={classes.empty}>{value}</span>
    </span>
  );

  const buildLeaf = (value: string) => <li className="leaf">"{value}"</li>;
  // expand element
  const toggle = (event) => {
    event.target.parentElement
      .querySelector('.nested')
      .classList.toggle('active');
    event.target.classList.toggle('node-down');
  };

  return (
    <div className={classes.treeContainer}>
      <ul>{processObject(data)}</ul>
    </div>
  );
};

export default RecursiveTreeView;
